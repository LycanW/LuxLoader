package dev.luxloader.mc;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.host.HostAdapter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reflective access to native game handles without a Minecraft build dependency. The 26.3 chain is
 * windowSurface -> private backend -> private device -> vkDevice; instance().vkInstance() and
 * getDeviceInfo() supply other facts. MinecraftMapping supports user overrides when members change.
 * Failed resolution returns empty with diagnostics rather than throwing through per-frame rendering.
 */
public final class MinecraftGraphicsAccess {

    private final MinecraftMapping mapping;
    private final ClassLoader gameLoader;

    /** Actual loader used for device-chain resolution; see resolveDevice. */
    private volatile ClassLoader activeLoader;
    private final boolean verbose;
    /** Per-hop resolution failures retained for diagnosis. */
    private final List<String> notes = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Fallback field lookup by declared type suffix only when named lookup fails. Accept exactly one
     * match; report ambiguity rather than guessing between fields. This tolerates private-member renaming
     * across runtime/development environments.
     */
    private static final java.util.Map<MinecraftMapping.Hookpoint, String> FIELD_TYPE_HINTS =
            java.util.Map.of(
                    MinecraftMapping.Hookpoint.SURFACE_BACKEND,
                    "com.mojang.renderpearl.backend.api.GpuSurfaceBackend",
                    MinecraftMapping.Hookpoint.SURFACE_BACKEND_DEVICE,
                    "com.mojang.renderpearl.backend.vulkan.VulkanDevice");

    private MinecraftGraphicsAccess(MinecraftMapping mapping, ClassLoader gameLoader,
                                   boolean verbose) {
        this.mapping = mapping;
        this.gameLoader = gameLoader;
        this.verbose = verbose;
    }

    /**
     * @param gameLoader loader resolving renderpearl classes
     * @param overrides optional target overrides
     * @param verbose whether to log resolution
     */
    public static MinecraftGraphicsAccess create(ClassLoader gameLoader,
                                                java.util.Map<String, String> overrides,
                                                boolean verbose) {
        MinecraftMapping mapping = new MinecraftMapping(verbose);
        if (overrides != null) {
            overrides.forEach(mapping::override);
        }
        return create(mapping, gameLoader, verbose);
    }

    /**
     * Reuses the bridge's mapping table so user overrides and diagnostics cannot diverge between two
     * independently resolved tables.
     * @param mapping existing mappings
     * @param gameLoader game loader
     * @param verbose whether to log resolution
     */
    public static MinecraftGraphicsAccess create(MinecraftMapping mapping, ClassLoader gameLoader,
                                                boolean verbose) {
        return new MinecraftGraphicsAccess(
                mapping == null ? new MinecraftMapping(verbose) : mapping,
                gameLoader == null ? MinecraftGraphicsAccess.class.getClassLoader() : gameLoader,
                verbose);
    }

    /** Convenience constructor discovering the client JAR loader. */
    public static MinecraftGraphicsAccess createDefault(boolean verbose) {
        return create(MinecraftGraphicsAccess.class.getClassLoader(), java.util.Map.of(), verbose);
    }

    /**
     * Retrieves the live block atlas through TextureManager, Identifier and AbstractTexture accessors,
     * retaining its actual image format and dimensions. Translate formats by Vulkan numeric value; report
     * UNDEFINED rather than guessing if unavailable.
     * @return atlas, or null on any failed hop so the plugin can fall back
     */
    public dev.luxloader.api.scene.SceneImage blockAtlas() {
        try {
            if (atlasChain == null) {
                ClassLoader loader = activeLoader != null ? activeLoader : gameLoader;
                atlasChain = new AtlasChain(loader);
            }
            return atlasChain.resolve(this);
        } catch (Exception | LinkageError e) {
            if (!atlasWarned) {
                atlasWarned = true;
                notes.add(tr("Block atlas: resolution failed (materials fall back to flat color) -> ") + e);
            }
            return null;
        }
    }

    private Object cachedSpriteListIdentity;
    private long cachedFluidRevision = Long.MIN_VALUE;
    private java.util.List<dev.luxloader.api.scene.AtlasSpriteRef> cachedSpriteRefs = java.util.List.of();
    private boolean spriteCatalogWarned;

    /** Source sprite names and atlas rectangles for pack-specific material maps. */
    public java.util.List<dev.luxloader.api.scene.AtlasSpriteRef> blockAtlasSprites() {
        try {
            if (atlasChain == null) {
                atlasChain = new AtlasChain(activeLoader != null ? activeLoader : gameLoader);
            }
            Object atlas = atlasChain.textureObject(this);
            if (atlas == null) {
                return java.util.List.of();
            }
            java.lang.reflect.Field field = atlas.getClass().getDeclaredField("sprites");
            field.setAccessible(true);
            Object identity = field.get(atlas);
            long fluidRevision = MinecraftFluidSurfaces.revision();
            if (identity == cachedSpriteListIdentity && fluidRevision == cachedFluidRevision) {
                return cachedSpriteRefs;
            }
            if (!(identity instanceof java.util.List<?> sprites)) {
                return java.util.List.of();
            }
            java.util.List<dev.luxloader.api.scene.AtlasSpriteRef> refs =
                    new java.util.ArrayList<>(sprites.size());
            for (Object sprite : sprites) {
                Class<?> type = sprite.getClass();
                Object contents = type.getMethod("contents").invoke(sprite);
                Class<?> contentType = contents.getClass();
                String id = contentType.getMethod("name").invoke(contents).toString();
                int width = ((Number) contentType.getMethod("width").invoke(contents)).intValue();
                int height = ((Number) contentType.getMethod("height").invoke(contents)).intValue();
                if (width <= 0 || height <= 0) {
                    continue;
                }
                float u0 = ((Number) type.getMethod("getU0").invoke(sprite)).floatValue();
                float v0 = ((Number) type.getMethod("getV0").invoke(sprite)).floatValue();
                float u1 = ((Number) type.getMethod("getU1").invoke(sprite)).floatValue();
                float v1 = ((Number) type.getMethod("getV1").invoke(sprite)).floatValue();
                refs.add(new dev.luxloader.api.scene.AtlasSpriteRef(
                        new dev.luxloader.api.scene.TextureRef(id, width, height),
                        u0, v0, u1, v1, MinecraftFluidSurfaces.contains(id)));
            }
            cachedSpriteListIdentity = identity;
            cachedFluidRevision = fluidRevision;
            cachedSpriteRefs = java.util.List.copyOf(refs);
            return cachedSpriteRefs;
        } catch (Exception | LinkageError e) {
            if (!spriteCatalogWarned) {
                spriteCatalogWarned = true;
                notes.add(tr("Block sprite catalog: read failed -> ") + e);
            }
            return java.util.List.of();
        }
    }

    private boolean atlasWarned;

    /** Cached atlas-chain resolution. */
    private static final class AtlasChain {

        private final java.lang.reflect.Method getTextureManager;
        private final java.lang.reflect.Method fromNamespaceAndPath;
        private final java.lang.reflect.Method getTextureById;
        private final java.lang.reflect.Method getTextureView;
        private final java.lang.reflect.Method getGpuTexture;
        private final java.lang.reflect.Method getFormat;
        private final java.lang.reflect.Method getWidth;
        private final java.lang.reflect.Method getHeight;
        private final Object textureId;
        private boolean formatReported;

        AtlasChain(ClassLoader loader) throws Exception {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Class<?> textureManager = Class.forName(
                    "net.minecraft.client.renderer.texture.TextureManager", false, loader);
            Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, loader);
            Class<?> abstractTexture = Class.forName(
                    "net.minecraft.client.renderer.texture.AbstractTexture", false, loader);
            Class<?> gpuTexture = Class.forName(
                    "com.mojang.renderpearl.api.textures.GpuTexture", false, loader);

            getTextureManager = minecraft.getMethod("getTextureManager");
            fromNamespaceAndPath = identifier.getMethod(
                    "fromNamespaceAndPath", String.class, String.class);
            getTextureById = textureManager.getMethod("getTexture", identifier);
            getTextureView = abstractTexture.getMethod("getTextureView");
            getGpuTexture = abstractTexture.getMethod("getTexture");
            getFormat = gpuTexture.getMethod("getFormat");
            getWidth = gpuTexture.getMethod("getWidth", int.class);
            getHeight = gpuTexture.getMethod("getHeight", int.class);
            // Construct the identifier directly; reading game static fields can trigger unwanted class initialization.
            textureId = fromNamespaceAndPath.invoke(null, "minecraft", "textures/atlas/blocks.png");
        }

        dev.luxloader.api.scene.SceneImage resolve(MinecraftGraphicsAccess access) throws Exception {
            String semantic = "blockAtlas";
            boolean reportFormat = !formatReported;
            formatReported = true;
            Object atlas = textureObject(access);
            if (atlas == null) {
                return null;
            }
            Object view = getTextureView.invoke(atlas);
            Object texture = getGpuTexture.invoke(atlas);
            if (view == null || texture == null) {
                return null;
            }
            long vkImage = access.vkImageOf(view).orElse(0L);
            if (vkImage == 0L) {
                return null;
            }
            int width = ((Number) getWidth.invoke(texture, 0)).intValue();
            int height = ((Number) getHeight.invoke(texture, 0)).intValue();

            // Map formats by Vulkan numeric ID, not enum name: host RGBA8_UNORM and API R8G8B8A8_UNORM refer to the same format. Use the host VulkanConst.toVk conversion before local lookup.
            dev.luxloader.api.gpu.GpuFormat format = dev.luxloader.api.gpu.GpuFormat.UNDEFINED;
            try {
                Object gpuFormat = getFormat.invoke(texture);
                if (gpuFormat != null) {
                    Class<?> gpuFormatClass = gpuFormat.getClass();
                    Class<?> vulkanConst = Class.forName(
                            "com.mojang.renderpearl.backend.vulkan.VulkanConst", false,
                            gpuFormatClass.getClassLoader());
                    java.lang.reflect.Method toVk = vulkanConst.getMethod("toVk", gpuFormatClass);
                    int vk = ((Number) toVk.invoke(null, gpuFormat)).intValue();
                    format = dev.luxloader.api.gpu.GpuFormat.fromVk(vk);
                    if (reportFormat) {
                        System.err.println("[LuxLoader] " + semantic + tr(": Minecraft format ") + gpuFormat
                                + " -> VkFormat " + vk + " -> " + format
                                + "，" + width + "x" + height);
                    }
                }
            } catch (Exception e) {
                access.notes.add(tr("Block atlas: VkFormat mapping failed (treated as unavailable) -> ") + e);
                System.err.println(tr("[LuxLoader] Block atlas: VkFormat mapping failed -> ") + e);
            }
            return new dev.luxloader.api.scene.SceneImage(semantic,
                    dev.luxloader.api.gpu.ImageHandle.vkImage(vkImage, "mc-" + semantic),
                    format, width, height);
        }

        Object textureObject(MinecraftGraphicsAccess access) throws Exception {
            Object minecraft = Class.forName("net.minecraft.client.Minecraft", false,
                    access.activeLoader != null ? access.activeLoader : access.gameLoader)
                    .getMethod("getInstance").invoke(null);
            if (minecraft == null) {
                return null;
            }
            Object manager = getTextureManager.invoke(minecraft);
            return manager == null ? null : getTextureById.invoke(manager, textureId);
        }
    }

    private AtlasChain atlasChain;

    /** Underlying mapping table for diagnostics. */
    public MinecraftMapping mapping() {
        return mapping;
    }

    /**
     * Actual game classloader, preferring the one that resolved the live device chain. Using another
     * loader can initialize a separate VulkanInstance class.
     */
    public ClassLoader gameLoader() {
        ClassLoader active = activeLoader;
        return active != null ? active : gameLoader;
    }

    // Device chain.

    /** Resolved device-chain handles. */
    public record DeviceChain(long device, long instance, String deviceName, String backendName,
                              Object deviceObject) {

        /** Whether a native device handle was obtained. */
        public boolean usable() {
            return device != 0L;
        }

        /** LWJGL's VkDevice retains its actual parent physical device. */
        public long physicalDevice() {
            try {
                Object vk = deviceObject.getClass().getMethod("vkDevice").invoke(deviceObject);
                Object physical = vk.getClass().getMethod("getPhysicalDevice").invoke(vk);
                return ((Number) physical.getClass().getMethod("address").invoke(physical)).longValue();
            } catch (ReflectiveOperationException | RuntimeException failure) { return 0L; }
        }

        public int instanceApiVersion() {
            try {
                Object vk = deviceObject.getClass().getMethod("vkDevice").invoke(deviceObject);
                Object capabilities = vk.getClass().getMethod("getCapabilitiesInstance").invoke(vk);
                return capabilities.getClass().getField("apiVersion").getInt(capabilities);
            } catch (ReflectiveOperationException | RuntimeException failure) { return 0; }
        }

        public java.util.Set<String> instanceExtensions() {
            try {
                Object owner = deviceObject.getClass().getMethod("instance").invoke(deviceObject);
                Object extensions = owner.getClass().getMethod("getEnabledExtensions").invoke(owner);
                if (extensions instanceof java.util.Set<?> values)
                    return values.stream().filter(String.class::isInstance).map(String.class::cast)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
            return java.util.Set.of();
        }

        /** Diagnostic summary. */
        public String describe() {
            return "VkDevice=0x" + Long.toHexString(device)
                    + " VkInstance=0x" + Long.toHexString(instance)
                    + tr(" device=") + (deviceName == null || deviceName.isBlank() ? tr("Unknown") : deviceName)
                    + tr(" backend=") + (backendName == null || backendName.isBlank() ? tr("Unknown") : backendName);
        }
    }

    /**
     * Resolves handles from Minecraft.windowSurface().
     * @param windowSurface frontend or backend surface
     * @return empty if any hop fails
     */
    public Optional<DeviceChain> resolveDevice(Object windowSurface) {
        if (windowSurface == null) {
            return Optional.empty();
        }
        // Resolve through the live game object's classloader to avoid initializing a second VulkanInstance class.
        ClassLoader loader = windowSurface.getClass().getClassLoader();
        if (loader != null) {
            activeLoader = loader;
        }

        Object backend = readMember(MinecraftMapping.Hookpoint.SURFACE_BACKEND, windowSurface)
                .orElse(null);
        if (backend == null) {
            notes.add(0, tr("windowSurface runtime type is ") + windowSurface.getClass().getName()
                    + tr("; implemented interfaces: ") + java.util.Arrays.toString(windowSurface.getClass().getInterfaces()));
            return Optional.empty();
        }

        Object device = readMember(MinecraftMapping.Hookpoint.SURFACE_BACKEND_DEVICE, backend)
                .orElse(null);
        if (device == null) {
            return Optional.empty();
        }

        long deviceHandle = readHandle(MinecraftMapping.Hookpoint.DEVICE_HANDLE, device)
                .orElse(0L);
        long instanceHandle = readInstanceHandle(device);

        String deviceName = readDeviceInfoString(device, "name").orElse("");
        String backendName = readDeviceInfoString(device, "backendName").orElse("");

        if (verbose) {
            System.out.println(tr("[LuxLoader][MC Graphics] Device chain resolved: ")
                    + new DeviceChain(deviceHandle, instanceHandle, deviceName, backendName, device)
                    .describe());
        }
        return Optional.of(new DeviceChain(deviceHandle, instanceHandle, deviceName, backendName,
                device));
    }

    /** DeviceInfo containing capabilities, extensions and depth conventions. */
    public Optional<Object> deviceInfo(Object deviceObject) {
        if (deviceObject == null) {
            return Optional.empty();
        }
        return invokeNoArg(deviceObject, "getDeviceInfo");
    }

    private long readInstanceHandle(Object device) {
        Object instance = invokeNoArg(device, "instance").orElse(null);
        if (instance == null) {
            return 0L;
        }
        return readHandle(MinecraftMapping.Hookpoint.INSTANCE_HANDLE, instance).orElse(0L);
    }

    // Textures.

    /**
     * Gets the underlying VkImage from a GpuTextureView. The final composition view intercepted at
     * blitFromTexture supplies frame.color.
     * @param textureView host view
     */
    public OptionalLong vkImageOf(Object textureView) {
        if (textureView == null) {
            return OptionalLong.empty();
        }
        Object texture = readMember(MinecraftMapping.Hookpoint.TEXTURE_VIEW_TEXTURE, textureView)
                .orElse(null);
        if (texture == null) {
            return OptionalLong.empty();
        }
        return readHandle(MinecraftMapping.Hookpoint.TEXTURE_IMAGE_HANDLE, texture);
    }

    /**
     * Reads the view's own getWidth(0)/getHeight(0), respecting its mip range.
     * @return {width,height}, or null if unavailable
     */
    public int[] sizeOf(Object textureView) {
        if (textureView == null) {
            return null;
        }
        Optional<Integer> width = invokeIntArg(textureView, "getWidth", 0);
        Optional<Integer> height = invokeIntArg(textureView, "getHeight", 0);
        if (width.isEmpty() || height.isEmpty() || width.get() <= 0 || height.get() <= 0) {
            return null;
        }
        return new int[] {width.get(), height.get()};
    }

    // Reflection helpers.

    /** Reads a field or invokes a no-argument method by name. */
    private Optional<Object> readMember(MinecraftMapping.Hookpoint hookpoint, Object instance) {
        ClassLoader loader = activeLoader != null ? activeLoader : gameLoader;
        Optional<MinecraftMapping.ResolvedHook> hook = mapping.resolve(hookpoint, loader);
        if (hook.isEmpty()) {
            notes.add(hookpoint + tr(": class unresolved (")
                    + String.join(" | ", hookpoint.candidates()) + "）");
            return Optional.empty();
        }
        MinecraftMapping.ResolvedHook resolved = hook.get();

        // Prefer public methods to private fields for compatibility.
        Optional<Object> viaMethod = resolved.findMethod().flatMap(method -> {
            try {
                return Optional.ofNullable(method.invoke(instance));
            } catch (ReflectiveOperationException | RuntimeException e) {
                notes.add(hookpoint + tr(": method ") + method.getName() + tr(" invocation failed (")
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + "）");
                return Optional.empty();
            }
        });
        if (viaMethod.isPresent()) {
            return viaMethod;
        }

        Optional<Object> viaField = resolved.readField(instance);
        if (viaField.isPresent()) {
            return viaField;
        }

        // Fallback matching by type.
        Optional<Object> structural = readFieldByType(hookpoint, instance, resolved);
        if (structural.isPresent()) {
            return structural;
        }

        notes.add(hookpoint + tr(": on ") + instance.getClass().getName() + tr(", neither member ")
                + resolved.methodName() + tr(" nor a field with the matching type was found")
                + tr(" (class fields: ") + describeFields(instance.getClass()) + "）");
        return Optional.empty();
    }

    /** Fallback field lookup by declared type, requiring exactly one candidate. Ambiguity is an explicit failure. */
    private Optional<Object> readFieldByType(MinecraftMapping.Hookpoint hookpoint, Object instance,
                                             MinecraftMapping.ResolvedHook resolved) {
        String hint = FIELD_TYPE_HINTS.get(hookpoint);
        if (hint == null) {
            return Optional.empty();
        }
        List<java.lang.reflect.Field> matches = new ArrayList<>();
        for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : safeFields(c)) {
                if (field.getType().getName().endsWith(hint)) {
                    matches.add(field);
                }
            }
        }
        if (matches.size() != 1) {
            if (matches.size() > 1) {
                notes.add(hookpoint + tr(": type lookup for ") + hint + tr(" found ") + matches.size()
                        + tr(" fields; ambiguous, refusing to guess"));
            }
            return Optional.empty();
        }
        try {
            java.lang.reflect.Field field = matches.get(0);
            field.setAccessible(true);
            Object value = field.get(instance);
            if (value != null) {
                notes.add(hookpoint + tr(": name lookup failed; resolved by type to field ") + field.getName()
                        + "（" + hint + "）");
                return Optional.of(value);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            notes.add(hookpoint + tr(": failed to read matching field (")
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "）");
        }
        return Optional.empty();
    }

    /** Lists fields as name:type for diagnostics. */
    private static String describeFields(Class<?> type) {
        StringBuilder sb = new StringBuilder();
        for (java.lang.reflect.Field field : safeFields(type)) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(field.getName()).append(':').append(field.getType().getSimpleName());
        }
        return sb.length() == 0 ? tr(" (unreadable)") : sb.toString();
    }

    /** getDeclaredFields can throw LinkageError for missing signature types. */
    private static java.lang.reflect.Field[] safeFields(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (LinkageError | RuntimeException e) {
            return new java.lang.reflect.Field[0];
        }
    }

    /**
     * Retrieves the acquired swapchain image via swapchainImages, currentImageIndex and
     * swapchainImageFormat. Blitting plugin output here replaces the original host blit without adding a
     * copy.
     */
    public HostAdapter.HostPresentImage presentImage(Object windowSurface) {
        if (windowSurface == null) {
            return HostAdapter.HostPresentImage.NONE;
        }
        Object backend = readMember(MinecraftMapping.Hookpoint.SURFACE_BACKEND, windowSurface)
                .orElse(null);
        if (backend == null) {
            return HostAdapter.HostPresentImage.NONE;
        }

        Object images = readMember(MinecraftMapping.Hookpoint.SWAPCHAIN_IMAGES, backend)
                .orElse(null);
        Object indexValue = readMember(MinecraftMapping.Hookpoint.SWAPCHAIN_IMAGE_INDEX, backend)
                .orElse(null);
        if (images == null || !(indexValue instanceof Number index)) {
            return HostAdapter.HostPresentImage.NONE;
        }

        // Before acquisition the image index may be a sentinel; bounds checks prevent invalid LongList access.
        long image = readSwapchainSlot(images, index.intValue());
        if (image == 0L) {
            return HostAdapter.HostPresentImage.NONE;
        }
        int format = readIntMember(MinecraftMapping.Hookpoint.SWAPCHAIN_FORMAT, backend, 0);
        int[] size = surfaceSize(windowSurface);
        return new HostAdapter.HostPresentImage(image, format,
                size == null ? 0 : size[0], size == null ? 0 : size[1]);
    }

    /** Reads a LongList slot, returning zero for invalid bounds/types. */
    private long readSwapchainSlot(Object images, int index) {
        if (index < 0) {
            return 0L;
        }
        try {
            var size = images.getClass().getMethod("size");
            size.setAccessible(true);
            Object count = size.invoke(images);
            if (count instanceof Number n && index >= n.intValue()) {
                notes.add(tr("Swapchain index ") + index + tr(" is out of range (total ") + count + tr(" images); bypassing presentation this frame"));
                return 0L;
            }
            var getLong = images.getClass().getMethod("getLong", int.class);
            getLong.setAccessible(true);
            Object value = getLong.invoke(images, index);
            return value instanceof Number n ? n.longValue() : 0L;
        } catch (ReflectiveOperationException | RuntimeException e) {
            notes.add(tr("Failed to read swapchain image array (") + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + "）");
            return 0L;
        }
    }

    /** Reads an int member, preferring a method over a field. */
    private int readIntMember(MinecraftMapping.Hookpoint hookpoint, Object instance, int fallback) {
        return readMember(hookpoint, instance)
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).intValue())
                .orElse(fallback);
    }

    /** Gets authoritative drawable dimensions from surface configuration. */
    /**
     * Destroy a host GPU object through its {@code destroy()} method, ignoring failure.
     *
     * <p>{@code destroy()} rather than {@code close()}: close() goes through the host's
     * view bookkeeping, which misbehaves when a view was never registered.
     *
     * <p>Silent by design -- these calls happen on failure and shutdown paths, where
     * throwing would be worse than leaking a handle that is about to die with the device.
     */
    static void destroyQuietly(Object handle) {
        if (handle == null) {
            return;
        }
        try {
            java.lang.reflect.Method destroy = handle.getClass().getMethod("destroy");
            destroy.setAccessible(true);
            destroy.invoke(handle);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // intentionally ignored
        }
    }

    /** Latest failure note, or empty. */
    public String lastNote() {
        return notes.isEmpty() ? "" : notes.get(notes.size() - 1);
    }

    public int[] surfaceSize(Object windowSurface) {
        try {
            var currentConfiguration = windowSurface.getClass().getMethod("currentConfiguration");
            currentConfiguration.setAccessible(true);
            Object optional = currentConfiguration.invoke(windowSurface);
            if (optional instanceof java.util.Optional<?> opt && opt.isPresent()) {
                Integer w = readIntByName(opt.get(), "width");
                Integer h = readIntByName(opt.get(), "height");
                if (w != null && h != null && w > 0 && h > 0) {
                    return new int[] {w, h};
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall back to field access.
        }
        Integer w = readIntByName(windowSurface, "width");
        Integer h = readIntByName(windowSurface, "height");
        return w == null || h == null ? null : new int[] {w, h};
    }

    /** Reads a named int, preferring a method over a field. */
    private Integer readIntByName(Object instance, String name) {
        try {
            var method = instance.getClass().getMethod(name);
            method.setAccessible(true);
            Object value = method.invoke(instance);
            return value instanceof Number n ? n.intValue() : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Try the field.
        }
        for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : safeFields(c)) {
                if (field.getName().equals(name)) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(instance);
                        return value instanceof Number n ? n.intValue() : null;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
    /**
     * Creates a display-sized texture on the host device because native external images cannot be wrapped
     * by the game's allocating texture constructor. Plugin output can be copied into this host-owned
     * texture and presented through its view, at the cost of an extra blit. Uses RGBA8_UNORM and
     * COPY_DST/TEXTURE_BINDING usage.
     * @param deviceObject host device
     * @param width display width
     * @param height display height
     * @return texture/view, or null to retain normal presentation
     */
    public PresentTexture createPresentTexture(Object deviceObject, int width, int height) {
        if (deviceObject == null || width <= 0 || height <= 0) {
            return null;
        }
        // Declared outside the try so every failure path can release them.
        // Each path that returns null after the texture exists must destroy it,
        // otherwise one host texture (and its view) leaks per failed attempt.
        Object texture = null;
        Object view = null;
        boolean success = false;
        try {
            // Infer types from device method signatures instead of guessing game class names.
            Method createTexture = null;
            // Select the overload beginning with String, not the Supplier<String> overload with the same arity.
            for (Method m : deviceObject.getClass().getMethods()) {
                if (m.getName().equals("createTexture") && m.getParameterCount() == 7
                        && m.getParameterTypes()[0] == String.class) {
                    createTexture = m;
                }
            }
            if (createTexture == null) {
                notes.add(tr("Device has no createTexture(String, int, GpuFormat, int, int, int, int)"));
                return null;
            }

            Class<?> textureClass = createTexture.getReturnType();

            // Frontend/backend createTextureView overloads differ. Match an assignable first texture parameter and prefer the shortest overload rather than selecting by arity alone.
            java.util.List<Method> viewCandidates = new java.util.ArrayList<>();
            for (Method m : deviceObject.getClass().getMethods()) {
                if (!m.getName().equals("createTextureView")) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length >= 1 && params[0].isAssignableFrom(textureClass)) {
                    viewCandidates.add(m);
                }
            }
            viewCandidates.sort(java.util.Comparator.comparingInt(Method::getParameterCount));
            Method createView = viewCandidates.isEmpty() ? null : viewCandidates.get(0);
            if (createView == null) {
                notes.add(tr("Device has no createTextureView accepting ") + textureClass.getSimpleName()
                        + tr(" as its input"));
                return null;
            }
            Class<?> formatClass = createTexture.getParameterTypes()[2];

            int usage = 0;
            int wanted = 0;
            for (String name : new String[] {"COPY_DST", "COPY_SRC", "TEXTURE_BINDING"}) {
                for (Field field : textureClass.getFields()) {
                    if (field.getName().endsWith(name) && field.getType() == int.class) {
                        usage |= field.getInt(null);
                        wanted++;
                        break;
                    }
                }
            }
            if (wanted < 2) {
                notes.add(tr("Texture usage constants unavailable; cannot create presentation texture"));
                return null;
            }

            Object format = null;
            Field chosen = null;
            for (String name : new String[] {"RGBA8_UNORM", "R8G8B8A8_UNORM", "B8G8R8A8_UNORM"}) {
                try {
                    Field field = formatClass.getField(name);
                    if (field.getType() == formatClass) {
                        format = field.get(null);
                        chosen = field;
                        break;
                    }
                } catch (NoSuchFieldException ignored) {
                    // Try the next candidate.
                }
            }
            if (format == null) {
                notes.add(tr("No suitable 8-bit UNORM GpuFormat; cannot create presentation texture"));
                return null;
            }

            createTexture.setAccessible(true);
            texture = createTexture.invoke(deviceObject, "luxloader-present",
                    usage, format, width, height, 1, 1);
            if (texture == null || !textureClass.isInstance(texture)) {
                notes.add(tr("createTexture returned no usable texture; cannot intercept presentation"));
                return null;
            }

            createView.setAccessible(true);
            // The single-argument overload takes a texture; the three-argument overload also takes full width/height.
            view = createView.getParameterCount() == 1
                    ? createView.invoke(deviceObject, texture)
                    // The backend three-argument view overload takes (texture, baseMipLevel, levelCount), not dimensions. For a one-mip texture use 0 and 1; passing display dimensions creates an invalid subresource range and black output (VUID 01478/01718).
                    : createView.invoke(deviceObject, texture, 0, 1);
            if (view == null) {
                notes.add(tr("createTextureView returned null; cannot intercept presentation"));
                return null;
            }

            long image = readHandle(MinecraftMapping.Hookpoint.TEXTURE_IMAGE_HANDLE, texture).orElse(0L);
            if (image == 0L) {
                notes.add(tr("Presentation texture exposes no VkImage handle; cannot intercept presentation"));
                return null;
            }
            notes.add(tr("Presentation texture created: ") + width + "x" + height + " " + chosen.getName() + " usage=0x"
                    + Integer.toHexString(usage));
            success = true;
            return new PresentTexture(image, view, texture, width, height);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            notes.add(tr("Failed to create presentation texture (") + e.getClass().getSimpleName() + ": " + e.getMessage() + "）");
            return null;
        } finally {
            if (!success) {
                // Partial creation: destroy whatever exists, view before texture.
                destroyQuietly(view);
                destroyQuietly(texture);
            }
        }
    }

    /**
     * Host-created presentation texture.
     * @param vkImage native blit target
     * @param view host view used by blitFromTexture
     * @param texture retained host texture owner
     */
    public record PresentTexture(long vkImage, Object view, Object texture, int width, int height) {
    }
    /** Resolution failure details retained for diagnosis. */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    /** Reads a native handle from a long or Vk wrapper member. */
    private OptionalLong readHandle(MinecraftMapping.Hookpoint hookpoint, Object instance) {
        return readMember(hookpoint, instance).stream()
                .mapToLong(MinecraftGraphicsAccess::toNativeHandle)
                .filter(handle -> handle != 0L)
                .findFirst();
    }

    /** Normalizes raw long handles and LWJGL wrappers exposing address() into a long. */
    private static long toNativeHandle(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            var address = value.getClass().getMethod("address");
            address.setAccessible(true);
            Object result = address.invoke(value);
            return result instanceof Number number ? number.longValue() : 0L;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0L;
        }
    }

    private Optional<Object> invokeNoArg(Object instance, String methodName) {
        try {
            var method = instance.getClass().getMethod(methodName);
            method.setAccessible(true);
            return Optional.ofNullable(method.invoke(instance));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<Integer> invokeIntArg(Object instance, String methodName, int argument) {
        try {
            var method = instance.getClass().getMethod(methodName, int.class);
            method.setAccessible(true);
            Object result = method.invoke(instance, argument);
            return result instanceof Number number ? Optional.of(number.intValue()) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Reads a DeviceInfo string component. */
    private Optional<String> readDeviceInfoString(Object deviceObject, String accessor) {
        return deviceInfo(deviceObject)
                .flatMap(info -> invokeNoArg(info, accessor))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(text -> !text.isBlank());
    }
}
