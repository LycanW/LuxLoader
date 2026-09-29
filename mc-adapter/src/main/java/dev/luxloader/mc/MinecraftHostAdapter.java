package dev.luxloader.mc;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuVendor;
import dev.luxloader.api.host.HostAdapter;
import dev.luxloader.api.gpu.ImageHandle;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Minecraft HostAdapter translates live device handles, captured composition textures and capability
 * facts into kernel contracts. Mod-loader hooks feed captureFrame at blitFromTexture; this adapter
 * does not install loader-specific hooks. The compatibility capture exposes color but not the host
 * frame graph's depth/motion vectors. Missing inputs remain null with unsupported capabilities. A
 * missing retained physical-device handle is resolved by the kernel using the host instance. Capture
 * and frame access run on the render thread; volatile state supports concurrent diagnostic reads.
 */
public final class MinecraftHostAdapter implements HostAdapter {

    /** Captured frame image. */
    public record CapturedFrame(Object textureView, long vkImage, int width, int height,
                                long frameIndex, int vkFormat, int usage,
                                long depthVkImage, int depthVkFormat, int depthUsage) {

        public CapturedFrame(Object textureView, long vkImage, int width, int height,
                             long frameIndex, int vkFormat, int usage) {
            this(textureView, vkImage, width, height, frameIndex, vkFormat, usage, 0L, 0, 0);
        }

        /** Whether a usable image was captured. */
        public boolean usable() {
            return vkImage != 0L && width > 0 && height > 0;
        }

        /**
         * Whether the host image has TEXTURE_BINDING usage (bit 4 in the observed mapping). Sampling without
         * that usage is invalid regardless of layout.
         */
        public boolean sampleable() {
            return (usage & 4) != 0;
        }

        public boolean depthSampleable() {
            return depthVkImage != 0L && depthVkFormat > 0 && (depthUsage & 4) != 0;
        }

        public String describe() {
            return "vkImage=0x" + Long.toHexString(vkImage) + " " + width + "x" + height
                    + tr(" frame=") + frameIndex
                    + " vkFormat=" + vkFormat
                    + " usage=0x" + Integer.toHexString(usage)
                    + (sampleable() ? "" : tr(" (no sampled usage bit)"));
        }
    }

    private final String loaderName;
    private final String gameVersion;
    /** Window surface used for frame capture and swapchain access. */
    private final Object windowSurface;
    private final MinecraftGraphicsAccess graphics;
    private final MinecraftMapping.Hookpoint[] requiredHookpoints;
    private final String unsupportedReason;

    private volatile MinecraftGraphicsAccess.DeviceChain deviceChain;

    /** Reusable host presentation texture, recreated on resize. */
    private volatile MinecraftGraphicsAccess.PresentTexture presentTexture;

    /** Latest presentation-target failure, cleared on success. */
    private volatile String presentImageNote;

    /** Failed dimensions, preventing repeated texture creation every frame. */
    private volatile int failedWidth = -1;
    private volatile int failedHeight = -1;

    /** VK_FORMAT_R8G8B8A8_UNORM presentation format matching GpuFormat.R8G8B8A8_UNORM. */
    private static final int VK_FORMAT_R8G8B8A8_UNORM = 37;
    private volatile GpuCapabilities capabilities;
    private volatile String enabledExtensionsNote;
    private volatile CapturedFrame captured;
    private volatile boolean hooksInstalled;
    private volatile long capturedFrameCount;
    private volatile long captureMissCount;
    private volatile long droppedFrameCount;
    private long frameCounter;

    private MinecraftHostAdapter(Object windowSurface, String loaderName, String gameVersion,
                                 MinecraftGraphicsAccess graphics,
                                 MinecraftGraphicsAccess.DeviceChain deviceChain,
                                 GpuCapabilities capabilities, String unsupportedReason) {
        this.windowSurface = windowSurface;
        this.loaderName = loaderName;
        this.gameVersion = gameVersion;
        this.graphics = graphics;
        this.deviceChain = deviceChain;
        this.capabilities = capabilities;
        this.unsupportedReason = unsupportedReason == null ? "" : unsupportedReason;
        this.requiredHookpoints = new MinecraftMapping.Hookpoint[] {
                MinecraftMapping.Hookpoint.SURFACE_BACKEND,
                MinecraftMapping.Hookpoint.SURFACE_BACKEND_DEVICE,
                MinecraftMapping.Hookpoint.DEVICE_HANDLE,
                MinecraftMapping.Hookpoint.TEXTURE_VIEW_TEXTURE,
                MinecraftMapping.Hookpoint.TEXTURE_IMAGE_HANDLE,
        };
        // This cache is process-wide because section uploads arrive through a static mixin.
        // A newly created host adapter must not inherit meshes from an earlier session.
        MinecraftCompiledScene.instance().clear();
    }

    /**
     * Probes and constructs an adapter, retaining failure capabilities for fallback. Only a null
     * windowSurface returns no adapter.
     * @param windowSurface host surface
     * @param gameLoader game classloader
     * @param loaderName mod loader name
     * @param gameVersion game version
     * @param overrides optional mapping overrides
     * @param verbose detailed resolution logging
     */
    public static MinecraftHostAdapter create(Object windowSurface, ClassLoader gameLoader,
                                              String loaderName, String gameVersion,
                                              java.util.Map<String, String> overrides,
                                              boolean verbose) {
        if (windowSurface == null) {
            return null;
        }
        return create(windowSurface, MinecraftGraphicsAccess.create(gameLoader, overrides, verbose),
                loaderName, gameVersion);
    }

    /**
     * Constructs using the existing graphics accessor/mapping so overrides and diagnostic claims match
     * actual access.
     * @param windowSurface host surface
     * @param graphics resolved accessor
     * @param loaderName mod loader name
     * @param gameVersion game version
     */
    public static MinecraftHostAdapter create(Object windowSurface,
                                              MinecraftGraphicsAccess graphics,
                                              String loaderName, String gameVersion) {
        if (windowSurface == null) {
            return null;
        }
        MinecraftGraphicsAccess access = graphics == null
                ? MinecraftGraphicsAccess.createDefault(false) : graphics;

        MinecraftGraphicsAccess.DeviceChain chain = access.resolveDevice(windowSurface).orElse(null);
        if (chain == null || !chain.usable()) {
            return new MinecraftHostAdapter(windowSurface, loaderName, gameVersion, access, null, null,
                    describeMissingChain(access));
        }

        MinecraftHostAdapter adapter = new MinecraftHostAdapter(windowSurface, loaderName, gameVersion,
                access, chain, null, "");
        adapter.capabilities = adapter.deriveCapabilities();
        return adapter;
    }

    private static String describeMissingChain(MinecraftGraphicsAccess graphics) {
        StringBuilder sb = new StringBuilder(tr("Cannot acquire the game's Vulkan device: "));
        sb.append(tr("the game may be using OpenGL, or integration mappings need updating ("));
        sb.append(graphics.mapping().failures().isEmpty()
                ? tr("no objects resolved in the device chain")
                : String.join("；", graphics.mapping().failures()));
        sb.append("）");
        return sb.toString();
    }

    // Frame capture called by integration hooks.

    /**
     * Captures the view intercepted at blitFromTexture.
     * @param textureView final host composition view
     * @return whether capture succeeded
     */
    public boolean captureFrame(Object textureView) {
        return captureFrame(textureView, 0, 0);
    }

    public boolean captureFrame(Object textureView, int vkFormat, int usage) {
        return captureFrame(textureView, vkFormat, usage, null, 0, 0);
    }

    public boolean captureFrame(Object textureView, int vkFormat, int usage,
                                Object depthView, int depthVkFormat, int depthUsage) {
        frameCounter++;
        if (textureView == null) {
            droppedFrameCount++;
            return false;
        }
        OptionalLong image = graphics.vkImageOf(textureView);
        int[] size = graphics.sizeOf(textureView);
        if (image.isEmpty() || size == null) {
            // Record capture failure so an inactive pipeline has an actionable cause.
            droppedFrameCount++;
            captureMissCount++;
            return false;
        }
        long depthVkImage = depthView == null ? 0L : graphics.vkImageOf(depthView).orElse(0L);
        captured = new CapturedFrame(textureView, image.getAsLong(), size[0], size[1], frameCounter,
                vkFormat, usage, depthVkImage, depthVkFormat, depthUsage);
        capturedFrameCount++;
        return true;
    }

    /** Clears the frame capture slot to prevent reusing stale handles next frame. */
    public void clearFrame() {
        captured = null;
    }

    /** Whether integration hooks are installed; host.frame_hook is unsupported until then. */
    public void markHooksInstalled(boolean installed) {
        this.hooksInstalled = installed;
    }

    /** Successful capture count. */
    public long capturedFrameCount() {
        return capturedFrameCount;
    }

    /** Capture failures from missing views or image handles. */
    public long droppedFrameCount() {
        return droppedFrameCount;
    }

    /** Current captured image, or null. */
    public CapturedFrame capturedFrame() {
        return captured;
    }

    // ------------------------------------------------------------------
    // HostAdapter
    // ------------------------------------------------------------------

    @Override
    public String hostName() {
        return "Minecraft " + gameVersion + " / " + loaderName;
    }

    @Override
    public String backendName() {
        MinecraftGraphicsAccess.DeviceChain chain = deviceChain;
        if (chain != null && chain.backendName() != null && !chain.backendName().isBlank()) {
            return chain.backendName();
        }
        return deviceChain == null ? "" : "Vulkan";
    }

    @Override
    public GpuCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public java.util.Optional<java.util.Set<String>> enabledDeviceExtensions() {
        MinecraftGraphicsAccess.DeviceChain chain = deviceChain;
        if (chain == null || chain.deviceObject() == null) {
            return java.util.Optional.of(java.util.Set.of());
        }
        try {
            java.lang.reflect.Field field = chain.deviceObject().getClass()
                    .getDeclaredField("enabledFeatures");
            field.setAccessible(true);
            Object featureSet = field.get(chain.deviceObject());
            Object names = featureSet.getClass().getMethod("extensions").invoke(featureSet);
            if (!(names instanceof java.util.Set<?> values)) {
                throw new IllegalStateException("enabledFeatures.extensions is not a Set");
            }
            java.util.Set<String> enabled = new java.util.HashSet<>();
            for (Object value : values) {
                if (value instanceof String name) enabled.add(name);
            }
            return java.util.Optional.of(java.util.Set.copyOf(enabled));
        } catch (ReflectiveOperationException | RuntimeException e) {
            enabledExtensionsNote = tr("Failed to query host enabled Vulkan extensions; treating them as disabled: ") + e;
            return java.util.Optional.of(java.util.Set.of());
        }
    }

    @Override
    public HostDevice device() {
        MinecraftGraphicsAccess.DeviceChain chain = deviceChain;
        if (chain == null || !chain.usable()) {
            return null;
        }
        // The host does not retain its physical-device handle; the kernel enumerates it from the instance.
        return new HostDevice(chain.instance(), chain.physicalDevice(), chain.device(), true,
                chain.instanceApiVersion(), chain.instanceExtensions());
    }

    @Override
    public boolean supportsSceneExtraction() {
        return cameraAccess() != null;
    }

    /**
     * Lazily creates scene extraction only when a game classloader is available, avoiding a worker thread
     * after failed device resolution.
     */
    private MinecraftCameraAccess cameraAccess() {
        if (cameraAccessTried) {
            return cameraAccess;
        }
        synchronized (this) {
            if (!cameraAccessTried) {
                MinecraftGraphicsAccess access = graphics;
                ClassLoader loader = access == null ? null : access.gameLoader();
                if (loader != null) {
                    try {
                        cameraAccess = new MinecraftCameraAccess(loader,
                                message -> noteWorld(message, true));
                    } catch (ReflectiveOperationException | LinkageError e) {
                        noteWorld(tr("Camera reflection chain unavailable: ") + e, true);
                    }
                }
                cameraAccessTried = true;
            }
        }
        return cameraAccess;
    }

    /**
     * Records extraction messages in the adapter's ring buffer and stderr because HostAdapter has no
     * Diagnostics service. Do not append to graphics.notes(), which returns an immutable snapshot. A
     * direct loader diagnostic connection remains preferable.
     */
    private void noteWorld(String message, boolean warning) {
        String line = tr("[World sample] ") + message;
        synchronized (worldNotes) {
            if (worldNotes.size() < 64) {
                worldNotes.add(line);
            }
        }
        if (WORLD_VERBOSE) {
            System.err.println((warning ? tr("[LuxLoader][Warning] ") : "[LuxLoader] ") + line);
        }
    }

    private final java.util.List<String> worldNotes =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Recent scene extraction log entries. */
    public java.util.List<String> worldNotes() {
        synchronized (worldNotes) {
            return java.util.List.copyOf(worldNotes);
        }
    }

    private static final boolean WORLD_VERBOSE =
            !"false".equalsIgnoreCase(System.getProperty("luxloader.world.verbose", "true"));

    /**
     * Nonblocking camera/world snapshot access; geometry extraction runs in the background and this method
     * consumes completed results.
     */
    @Override
    public dev.luxloader.api.scene.SceneSnapshot sceneMetadata() {
        long frame = ++sceneFrameIndex;
        MinecraftCameraAccess camera = cameraAccess();
        if (camera == null) {
            return dev.luxloader.api.scene.SceneSnapshot.empty(frame);
        }
        updateWorldIdentity(camera.worldIdentity());
        return new dev.luxloader.api.scene.SceneSnapshot(
                camera.currentCamera(), java.util.List.of(),
                currentEnvironment(),
                java.util.Map.of(), frame, camera.levelPresent(), false,
                0L, 0L, java.util.List.of(), java.util.List.of(), sceneImages(),
                java.util.List.of(), MinecraftCompiledScene.instance().revision(),
                graphics.blockAtlasSprites(), MinecraftDynamicScene.snapshot());
    }

    @Override
    public dev.luxloader.api.scene.SceneGeometryFeed sceneGeometryFeed() {
        return MinecraftCompiledScene.instance();
    }

    @Override
    public dev.luxloader.api.scene.SceneSnapshot sceneSnapshot() {
        long frame = ++sceneFrameIndex;
        MinecraftCameraAccess camera = cameraAccess();
        if (camera == null) {
            return dev.luxloader.api.scene.SceneSnapshot.empty(frame);
        }
        Object world = camera.worldIdentity();
        updateWorldIdentity(world);
        MinecraftCompiledScene.Snapshot compiled = MinecraftCompiledScene.instance().snapshot();
        return new dev.luxloader.api.scene.SceneSnapshot(
                camera.currentCamera(),
                java.util.List.of(),
                currentEnvironment(),
                java.util.Map.of(),
                frame,
                world != null,
                false,
                0L, 0L,
                java.util.List.of(),
                java.util.List.of(),
                sceneImages(),
                compiled.meshes(),
                compiled.revision(),
                graphics.blockAtlasSprites(), MinecraftDynamicScene.snapshot());
    }

    /** Refresh host texture handles each frame to avoid destroyed images after resource reload. */
    private java.util.List<dev.luxloader.api.scene.SceneImage> sceneImages() {
        MinecraftGraphicsAccess access = graphics;
        if (access == null) {
            return java.util.List.of();
        }
        java.util.List<dev.luxloader.api.scene.SceneImage> images = new java.util.ArrayList<>(1);
        dev.luxloader.api.scene.SceneImage atlas = access.blockAtlas();
        if (atlas != null) {
            images.add(atlas);
        }
        for (var mesh : MinecraftDynamicScene.snapshot()) {
            if (!images.contains(mesh.albedo())) images.add(mesh.albedo());
        }
        return java.util.List.copyOf(images);
    }

    private volatile MinecraftCameraAccess cameraAccess;
    private dev.luxloader.api.scene.ResourceAccess resourceAccess;

    @Override public dev.luxloader.api.scene.ResourceAccess resources() {
        if (resourceAccess == null) {
            try { resourceAccess = new MinecraftResourceAccess(getClass().getClassLoader()); }
            catch (ReflectiveOperationException | LinkageError e) {
                noteWorld(tr("Resource interface unavailable: ") + e, true);
                resourceAccess = dev.luxloader.api.scene.ResourceAccess.EMPTY;
            }
        }
        return resourceAccess;
    }
    private MinecraftEnvironmentAccess environmentAccess;
    private boolean environmentAccessTried;

    private dev.luxloader.api.scene.SceneSnapshot.Environment currentEnvironment() {
        if (!environmentAccessTried) {
            environmentAccessTried = true;
            try {
                environmentAccess = new MinecraftEnvironmentAccess(getClass().getClassLoader(),
                        message -> noteWorld(message, true));
            } catch (ReflectiveOperationException | LinkageError e) {
                noteWorld(tr("Environment interface unavailable: ") + e, true);
            }
        }
        return environmentAccess == null ? dev.luxloader.api.scene.SceneSnapshot.Environment.DEFAULT
                : environmentAccess.read();
    }
    private Object capturedWorldIdentity;

    private void updateWorldIdentity(Object world) {
        if (world != capturedWorldIdentity) {
            // Section uploads can precede the first frame snapshot. Preserve those
            // uploads on the initial null -> world transition; clear when leaving
            // or replacing a world that this adapter has already observed.
            if (capturedWorldIdentity != null) {
                MinecraftCompiledScene.instance().clear();
            }
            capturedWorldIdentity = world;
        }
    }
    private volatile boolean cameraAccessTried;
    private long sceneFrameIndex;

    /** Current presentation override set by the UI composition hook. */
    private volatile HostPresentImage frameTargetOverride;

    /**
     * Overrides this frame's presentation target before GuiRenderer.render with the main color view for UI
     * composition. Null/unusable values restore the default presentation-texture path.
     */
    public void setFrameTargetOverride(long vkImage, int vkFormat, int width, int height) {
        frameTargetOverride = (vkImage == 0L || width <= 0 || height <= 0)
                ? null : new HostPresentImage(vkImage, vkFormat, width, height);
    }

    /** Whether a presentation override exists. */
    public boolean hasFrameTargetOverride() {
        return frameTargetOverride != null;
    }

    /** Scene extraction summary for loader logs. */
    public String worldDescribe() {
        MinecraftCameraAccess camera = cameraAccess();
        MinecraftCompiledScene.Snapshot scene = MinecraftCompiledScene.instance().snapshot();
        return camera == null ? tr("World integration unavailable (cannot acquire camera state)")
                : tr("Compiled section meshes: ") + scene.meshes().size() + tr(" sections / revision ") + scene.revision();
    }

    @Override
    public void registerCapabilities(Consumer<CapabilityDescriptor> sink) {
        if (sink == null) {
            return;
        }
        MinecraftGraphicsAccess.DeviceChain chain = deviceChain;

        if (chain == null || !chain.usable()) {
            sink.accept(CapabilityDescriptor.unsupported(CapabilityDescriptor.Ids.VULKAN_BACKEND,
                    hostName(), unsupportedReason));
            sink.accept(CapabilityDescriptor.unsupported(CapabilityDescriptor.Ids.DEVICE_ACCESSIBLE,
                    hostName(), unsupportedReason));
            sink.accept(CapabilityDescriptor.unsupported(CapabilityDescriptor.Ids.SCENE_EXTRACTION,
                    hostName(), tr("Device chain unavailable: ") + unsupportedReason));
            return;
        }

        sink.accept(CapabilityDescriptor.of(CapabilityDescriptor.Ids.VULKAN_BACKEND,
                CapabilityLevel.NATIVE, hostName() + "：" + chain.describe()));
        sink.accept(CapabilityDescriptor.of(CapabilityDescriptor.Ids.DEVICE_ACCESSIBLE,
                CapabilityLevel.NATIVE, tr("Native VkDevice and VkInstance acquired")));

        GpuCapabilities caps = capabilities;
        if (caps != null) {
            sink.accept(CapabilityDescriptor.of(CapabilityDescriptor.Ids.DEVICE_INFO,
                    CapabilityLevel.PARTIAL, tr("Host DeviceInfo: ")
                            + caps.deviceName() + tr(", extensions ") + caps.extensions().size() + tr("MinecraftHostAdapter.f9d529eacd", " total")));
        } else {
            sink.accept(CapabilityDescriptor.unsupported(CapabilityDescriptor.Ids.DEVICE_INFO,
                    hostName(), tr("Host does not expose DeviceInfo")));
        }

        // The integration layer installs hooks; report unavailable until installation completes.
        sink.accept(hooksInstalled
                ? CapabilityDescriptor.of(CapabilityDescriptor.Ids.FRAME_HOOK,
                        CapabilityLevel.NATIVE, tr("Attached to blitFromTexture / present"))
                : CapabilityDescriptor.unsupported(CapabilityDescriptor.Ids.FRAME_HOOK,
                        hostName(), tr("Frame hooks have not been installed")));

        sink.accept(hooksInstalled
                ? CapabilityDescriptor.of(CapabilityDescriptor.Ids.RESIZE_SIGNAL,
                        CapabilityLevel.PARTIAL, tr("Compare sizes each frame (host exposes no configure signal)"))
                : CapabilityDescriptor.unsupported(CapabilityDescriptor.Ids.RESIZE_SIGNAL,
                        hostName(), tr("Cannot compare sizes without a frame hook")));

        // Explicitly report unavailable inputs so plugins can choose fallback paths.
        sink.accept(CapabilityDescriptor.unsupported(CapabilityDescriptor.Ids.MOTION_VECTORS_AVAILABLE,
                hostName(), tr("blitFromTexture exposes only the composited image; motion vectors remain inside the host frame graph")));
        // Host chunk meshes are available while dynamic extraction is partial; plugins may retain host drawing for missing categories.
        sink.accept(supportsSceneExtraction()
                ? CapabilityDescriptor.of(CapabilityDescriptor.Ids.SCENE_EXTRACTION,
                        CapabilityLevel.PARTIAL, worldDescribe())
                : CapabilityDescriptor.unsupported(CapabilityDescriptor.Ids.SCENE_EXTRACTION,
                        hostName(), tr("Camera state or compiled mesh integration unavailable")));
        // Replacing blitFromTexture's source displays plugin output through an extra fullscreen copy into a host texture. Report PARTIAL for this compatibility path because external images cannot be wrapped directly.
        sink.accept(hooksInstalled
                ? CapabilityDescriptor.of(CapabilityDescriptor.Ids.PRESENT_INTERCEPT,
                        CapabilityLevel.PARTIAL, tr("Replace the blitFromTexture source (one additional fullscreen blit)"))
                : CapabilityDescriptor.unsupported(CapabilityDescriptor.Ids.PRESENT_INTERCEPT,
                        hostName(), tr("Cannot replace the presentation source without a frame hook")));
    }

    /**
     * Returns a reusable display-sized host texture, not the swapchain image, so the host can present
     * through its own GpuTextureView. NONE preserves ordinary rendering when
     * backend/acquisition/allocation is unavailable.
     */
    @Override
    public HostPresentImage presentImage() {
        // For UI composition, write plugin output into the main target before HUD drawing. The host then overlays native-resolution HUD and presents normally; replacing the final world-plus-HUD source later would discard the HUD.
        HostPresentImage override = frameTargetOverride;
        if (override != null && override.isUsable()) {
            presentImageNote = null;
            return override;
        }
        if (windowSurface == null) {
            presentImageNote = tr("windowSurface unavailable");
            return HostPresentImage.NONE;
        }
        try {
            int[] size = graphics.surfaceSize(windowSurface);
            if (size == null || size[0] <= 0 || size[1] <= 0) {
                presentImageNote = tr("Surface dimensions unavailable (surfaceSize returned ")
                        + (size == null ? "null" : size[0] + "x" + size[1]) + "）";
                return HostPresentImage.NONE;
            }
            MinecraftGraphicsAccess.PresentTexture texture = presentTexture(size[0], size[1]);
            if (texture == null) {
                presentImageNote = presentImageNote == null
                        ? tr("Failed to create presentation texture") : presentImageNote;
                return HostPresentImage.NONE;
            }
            presentImageNote = null;
            return new HostPresentImage(texture.vkImage(), VK_FORMAT_R8G8B8A8_UNORM,
                    texture.width(), texture.height());
        } catch (RuntimeException | LinkageError e) {
            presentImageNote = tr("Presentation target acquisition threw: ") + e;
            return HostPresentImage.NONE;
        }
    }

    @Override
    public String presentImageNote() {
        return presentImageNote;
    }

    /**
     * Destroy the cached present texture and its view.
     *
     * <p>Evidence: with the host-device release hook already in place, validation still
     * reported exactly two leaked objects at vkDestroyDevice --
     * {@code VkImage 0x..84, VkImageView 0x..85} -- which are precisely this texture and
     * its view. Nothing in the repository released them.
     *
     * <p>Uses {@code destroy()} rather than {@code close()}: close() goes through view
     * refcounting, and when the view was never created addViews() was never called, so
     * the counter would go negative and throw.
     *
     * <p>Order matters -- view first, otherwise it dangles on a destroyed image.
     */
    @Override
    public void releaseHostResources() {
        MinecraftCompiledScene.instance().clear();
        MinecraftGraphicsAccess.PresentTexture cached = presentTexture;
        presentTexture = null;
        if (cached == null) {
            return;
        }
        destroyQuietly(cached.view());
        destroyQuietly(cached.texture());
    }

    private static void destroyQuietly(Object handle) {
        if (handle == null) {
            return;
        }
        try {
            java.lang.reflect.Method destroy = handle.getClass().getMethod("destroy");
            destroy.setAccessible(true);
            destroy.invoke(handle);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Releasing must never throw into the host's shutdown path.
        }
    }

    /** Gets or creates the presentation texture, recreating it on resize to avoid stale views/dimensions. */
    private MinecraftGraphicsAccess.PresentTexture presentTexture(int width, int height) {
        MinecraftGraphicsAccess.PresentTexture cached = presentTexture;
        if (cached != null && cached.width() == width && cached.height() == height) {
            return cached;
        }
        MinecraftGraphicsAccess.DeviceChain chain = deviceChain;
        if (chain == null) {
            presentImageNote = tr("Device chain empty (host device not attached yet)");
            return null;
        }
        // After allocation fails at a size, avoid per-frame retries/log growth. Retry only after dimensions change.
        if (width == failedWidth && height == failedHeight) {
            return null;
        }
        MinecraftGraphicsAccess.PresentTexture created =
                graphics.createPresentTexture(chain.deviceObject(), width, height);
        // A size change replaces the cached texture. Destroy the previous one first,
        // otherwise every resize leaks a host texture and its view.
        MinecraftGraphicsAccess.PresentTexture previous = presentTexture;
        presentTexture = created;
        if (previous != null && previous != created) {
            MinecraftGraphicsAccess.destroyQuietly(previous.view());
            MinecraftGraphicsAccess.destroyQuietly(previous.texture());
        }
        if (created == null) {
            failedWidth = width;
            failedHeight = height;
            String note = graphics.lastNote();
            presentImageNote = tr("Failed to create presentation texture")
                    + (note.isEmpty() ? "" : "：" + note);
        } else {
            failedWidth = -1;
            failedHeight = -1;
        }
        return created;
    }

    /** Forwards adapter resolution notes so reports include failure causes rather than only unavailable status. */
    @Override
    public java.util.List<String> notes() {
        java.util.List<String> result = new java.util.ArrayList<>(
                graphics == null ? java.util.List.of() : graphics.notes());
        if (enabledExtensionsNote != null) result.add(enabledExtensionsNote);
        return java.util.List.copyOf(result);
    }

    /** Host presentation replacement source, or null. */
    public Object presentTextureView() {
        MinecraftGraphicsAccess.PresentTexture texture = presentTexture;
        return texture == null ? null : texture.view();
    }

    @Override
    public HostFrameTextures frameTextures() {
        CapturedFrame frame = captured;
        if (frame == null || !frame.usable()) {
            return HostFrameTextures.EMPTY;
        }

        // Identity of this image, settled from the game's own bytecode:
        //     Minecraft.renderFrame -> gameRenderer.mainRenderTarget().getColorTextureView()
        // i.e. the composed frame at render resolution, and it is the blit SOURCE
        // (the blit destination is swapchainImages.getLong(currentImageIndex)):
        //     vkCmdBlitImage(cmd, view.texture().vkImage(), /*srcLayout=*/1,
        //                    swapchainImages.getLong(currentImageIndex), /*dstLayout=*/7, ...)
        // A GpuTextureView can only ever wrap a VMA-created VulkanGpuTexture, never a raw
        // swapchain handle, so this image is NOT the swapchain image. The old comment here
        // claimed otherwise and the two slots simply held the same variable; that is a
        // naming artefact, not a fact about the image. See docs/FIELD-NOTES.md 9.2.
        ImageHandle image = ImageHandle.vkImage(frame.vkImage(),
                "mc-frame-" + frame.frameIndex());

        // Hand the real frame over as scene colour. An earlier build withheld it after a
        // device loss, on the theory that it was an unsampleable swapchain image. The loss
        // was actually caused by our own consumer-side defects -- a barrier emitted on a
        // host-owned image starting from UNDEFINED (which discards its contents), and a
        // storage image left in SHADER_READ_ONLY_OPTIMAL. Both are fixed in the core.
        // Withholding the frame is what kept the effect from ever being produced.
        //
        // renderpearl keeps every game-owned image in VK_IMAGE_LAYOUT_GENERAL for its whole
        // life and synchronises with global memory barriers, never layout transitions, so
        // the core must not transition this image either.
        boolean sampleable = frame.sampleable();
        ImageHandle depth = frame.depthSampleable()
                ? ImageHandle.vkImage(frame.depthVkImage(), "mc-depth-" + frame.frameIndex())
                : null;
        return new HostFrameTextures(
                sampleable ? image : null,   // color: composed frame at render resolution
                depth,
                null,           // motionVectors: same
                null,           // exposure: same
                null,           // ui: already composited into color
                image,          // swapchain: still the same handle today (see note below)
                frame.width(), frame.height(),   // render resolution == colour texture size
                frame.width(), frame.height(),
                frame.vkFormat(), frame.depthSampleable() ? frame.depthVkFormat() : 0);
    }

    @Override
    public HostFrameHook frameHook() {
        // Loader-specific mixins/events install hooks; keep this adapter independent of the mod loader.
        return HostFrameHook.NONE;
    }

    @Override
    public boolean hasReliableResizeSignal() {
        // Without a host configure signal, compare dimensions each frame.
        return false;
    }

    /** Graphics accessor for further adapter reflection. */
    public MinecraftGraphicsAccess graphics() {
        return graphics;
    }

    /** Resolved device chain, or null on failure. */
    public MinecraftGraphicsAccess.DeviceChain deviceChain() {
        return deviceChain;
    }

    /** One-line diagnostic status. */
    public String describe() {
        MinecraftGraphicsAccess.DeviceChain chain = deviceChain;
        StringBuilder sb = new StringBuilder();
        sb.append(tr("Host=")).append(hostName())
                .append(tr(" device=")).append(chain == null ? tr("Unavailable") : "0x" + Long.toHexString(chain.device()))
                .append(tr(" captured frames=")).append(capturedFrameCount)
                .append(tr(" dropped frames=")).append(droppedFrameCount);
        if (chain == null && !unsupportedReason.isEmpty()) {
            sb.append(System.lineSeparator()).append(tr("  Reason: ")).append(unsupportedReason);
        }
        return sb.toString();
    }

    // Capability derivation.

    /**
     * Derives observed capabilities from DeviceInfo, leaving unavailable values zero/empty. Native
     * enumeration can supplement missing Vulkan version information; never fabricate plausible
     * capabilities.
     */
    private GpuCapabilities deriveCapabilities() {
        MinecraftGraphicsAccess.DeviceChain chain = deviceChain;
        if (chain == null) {
            return null;
        }
        Object deviceObject = chain.deviceObject();
        Object info = graphics.deviceInfo(deviceObject).orElse(null);
        if (info == null) {
            // Return conservative device-existence capabilities when DeviceInfo is unavailable.
            return new GpuCapabilities(chain.deviceName().isBlank() ? tr("Minecraft graphics device")
                    : chain.deviceName(), GpuVendor.UNKNOWN, 0, 0, 0,
                    chain.backendName(), 0, Set.of(), Set.of(), 0, 0, 0f, 0L, 0);
        }

        String name = stringOf(info, "name").orElse(chain.deviceName());
        String vendorName = stringOf(info, "vendorName").orElse("");
        String driverInfo = stringOf(info, "driverInfo").orElse("");
        float timestampPeriod = floatOf(info, "timestampPeriod").orElse(0f);
        Set<String> extensions = stringSetOf(info, "underlyingExtensions");

        Object limits = objectOf(info, "limits").orElse(null);
        int maxImageDimension = limits == null ? 0 : intOf(limits, "maxTextureSize").orElse(0);

        return new GpuCapabilities(
                name.isBlank() ? tr("Minecraft graphics device") : name,
                vendorOf(vendorName),
                0, 0, 0,
                driverInfo,
                // DeviceInfo lacks the Vulkan API version. Supplement from the host instance where possible; otherwise keep zero so minimum-version checks fail conservatively.
                0,
                extensions,
                Set.of(),
                0, 0,
                timestampPeriod,
                0L,
                maxImageDimension);
    }

    /**
     * Maps vendor strings through display names, stable keys and known aliases such as AMD/ATI. Return
     * UNKNOWN instead of guessing, since vendor selection can control native SDK availability.
     */
    static GpuVendor vendorOf(String vendorName) {
        if (vendorName == null || vendorName.isBlank()) {
            return GpuVendor.UNKNOWN;
        }
        String lower = vendorName.toLowerCase(java.util.Locale.ROOT);

        // Try aliases observed in actual driver strings first.
        for (java.util.Map.Entry<String, GpuVendor> entry : VENDOR_ALIASES.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        for (GpuVendor vendor : GpuVendor.values()) {
            if (vendor == GpuVendor.UNKNOWN) {
                continue;
            }
            if (lower.contains(vendor.displayName().toLowerCase(java.util.Locale.ROOT))
                    || lower.contains(vendor.key())) {
                return vendor;
            }
        }
        return GpuVendor.UNKNOWN;
    }

    /**
     * Ordered aliases prioritize hardware vendors over driver projects, e.g. Intel before Mesa in a
     * combined driver string. LinkedHashMap preserves that matching order.
     */
    private static final java.util.Map<String, GpuVendor> VENDOR_ALIASES = buildVendorAliases();

    private static java.util.Map<String, GpuVendor> buildVendorAliases() {
        java.util.Map<String, GpuVendor> aliases = new java.util.LinkedHashMap<>();
        aliases.put("advanced micro devices", GpuVendor.AMD);
        aliases.put("ati technologies", GpuVendor.AMD);
        aliases.put("amd", GpuVendor.AMD);
        aliases.put("nvidia", GpuVendor.NVIDIA);
        aliases.put("intel", GpuVendor.INTEL);
        aliases.put("imgtec", GpuVendor.IMAGINATION);
        aliases.put("imagination", GpuVendor.IMAGINATION);
        aliases.put("qualcomm", GpuVendor.QUALCOMM);
        aliases.put("microsoft", GpuVendor.MICROSOFT);
        aliases.put("arm", GpuVendor.ARM);
        // Driver vendor names come last because they may differ from hardware vendors.
        aliases.put("mesa", GpuVendor.MESA);
        return java.util.Collections.unmodifiableMap(aliases);
    }

    // DeviceInfo reflection.

    private static Optional<Object> objectOf(Object target, String accessor) {
        try {
            var method = target.getClass().getMethod(accessor);
            method.setAccessible(true);
            return Optional.ofNullable(method.invoke(target));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> stringOf(Object target, String accessor) {
        return objectOf(target, accessor).filter(String.class::isInstance).map(String.class::cast);
    }

    private static Optional<Integer> intOf(Object target, String accessor) {
        return objectOf(target, accessor).filter(Number.class::isInstance)
                .map(value -> ((Number) value).intValue());
    }

    private static Optional<Float> floatOf(Object target, String accessor) {
        return objectOf(target, accessor).filter(Number.class::isInstance)
                .map(value -> ((Number) value).floatValue());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> stringSetOf(Object target, String accessor) {
        return objectOf(target, accessor)
                .filter(Set.class::isInstance)
                .map(value -> (Set<String>) value)
                .filter(set -> set.stream().allMatch(String.class::isInstance))
                .orElse(Set.of());
    }

    @Override
    public String toString() {
        return "MinecraftHostAdapter{" + hostName() + tr(", device chain=")
                + (deviceChain == null ? tr("none") : deviceChain.describe()) + "}";
    }
}
