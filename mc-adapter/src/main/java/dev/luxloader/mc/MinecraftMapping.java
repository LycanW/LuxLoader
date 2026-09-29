package dev.luxloader.mc;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minecraft hook mappings verified against the local unobfuscated 26.3 JAR with javap. Graphics
 * abstractions live in renderpearl; retain candidate lists and user overrides for future changes.
 * BLIT_TO_SCREEN intercepts final composition before present, after game command submission, for
 * compatibility effects.
 */
public final class MinecraftMapping {

    /** Loader-defined logical hook names mapped to version-specific classes/members. */
    public enum Hookpoint {
        /** GpuDevice entry point for textures, buffers, samplers, encoders and surfaces. */
        GPU_DEVICE("Graphics device interface",
                List.of("com.mojang.renderpearl.api.device.GpuDevice"),
                "Graphics abstraction entry point for backend, device name, extension and limit queries."),

        /**
         * DeviceInfo exposes backend/vendor names, extensions, limits, features and isZZeroToOne, avoiding
         * backend/depth guesses from game versions.
         */
        DEVICE_INFO("Device information",
                List.of("com.mojang.renderpearl.api.device.DeviceInfo"),
                "Backend, vendor, extensions, limits and features used for capability negotiation."),

        /** Swapchain surface, the final-output integration point. */
        BLIT_TO_SCREEN("Pre-presentation composition",
                List.of("com.mojang.renderpearl.frontend.FrontendGpuSurface#blitFromTexture",
                        "com.mojang.renderpearl.api.device.GpuSurface#blitFromTexture"),
                "Composite a texture into the swapchain; integration point for upscaling and frame generation."),

        /** GpuSurface.present; pacing/frame-generation integrations also need this presentation step. */
        PRESENT("Presentation",
                List.of("com.mojang.renderpearl.frontend.FrontendGpuSurface#present",
                        "com.mojang.renderpearl.api.device.GpuSurface#present"),
                "Submit the swapchain image. Frame generation must control this to schedule presentation."),

        /** GpuSurface.acquireNextTexture, observed with configure for swapchain changes. */
        ACQUIRE_TEXTURE("Acquire swapchain image",
                List.of("com.mojang.renderpearl.frontend.FrontendGpuSurface#acquireNextTexture",
                        "com.mojang.renderpearl.api.device.GpuSurface#acquireNextTexture"),
                "Acquire a writable image each frame and detect size changes."),

        /** GpuSurface.configure supplies actual surface dimensions/mode, more authoritative than window events. */
        SURFACE_CONFIGURE("Swapchain configuration",
                List.of("com.mojang.renderpearl.frontend.FrontendGpuSurface#configure",
                        "com.mojang.renderpearl.api.device.GpuSurface#configure"),
                "Observe size and presentation mode changes to rebuild size-dependent resources."),

        /**
         * Host FrameGraphBuilder supports pass declaration, external imports, internal resources and
         * execution. Compatibility hooks may schedule through it; plugin scene/resource ownership remains
         * governed by LuxLoader contracts.
         */
        FRAME_GRAPH("Host frame graph",
                List.of("com.mojang.blaze3d.framegraph.FrameGraphBuilder"),
                "The game's frame graph; preferred integration point for pipeline passes."),

        /** VulkanBackend.checkBackendAvailable probes backend availability directly. */
        BACKEND_VULKAN("Vulkan backend",
                List.of("com.mojang.renderpearl.backend.vulkan.VulkanBackend"),
                "Vulkan backend implementation used to identify support before device creation."),
        BACKEND_OPENGL("OpenGL backend",
                List.of("com.mojang.renderpearl.backend.opengl.GlBackend"),
                "OpenGL backend implementation used to identify the active graphics backend."),

        // Verified 26.3 handle chain: Minecraft.windowSurface -> private frontend backend -> private surface device -> vkDevice; instance().vkInstance() and getDeviceInfo() supply additional facts. Private members use overridable candidate mappings because names can change across versions.

        /** FrontendGpuSurface's private backend field holding the actual backend surface and device. */
        SURFACE_BACKEND("Surface backend",
                List.of("com.mojang.renderpearl.frontend.FrontendGpuSurface#backend"),
                "Backend implementation wrapped by the frontend surface; required to locate the device."),

        /** Device held by the backend surface's private field. */
        SURFACE_BACKEND_DEVICE("Backend surface device",
                List.of("com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface#device"),
                "Vulkan surface device object at the end of the device chain."),

        /** Native VkDevice handle. */
        DEVICE_HANDLE("VkDevice handle",
                List.of("com.mojang.renderpearl.backend.vulkan.VulkanDevice#vkDevice"),
                "Native device handle wrapped by core as its device view."),

        /** Native VkInstance handle obtained through instance(). */
        INSTANCE_HANDLE("VkInstance handle",
                List.of("com.mojang.renderpearl.backend.vulkan.VulkanInstance#vkInstance"),
                "Native instance handle for capability probing and function resolution."),

        /** Underlying texture of a texture view. */
        TEXTURE_VIEW_TEXTURE("Texture underlying a texture view",
                List.of("com.mojang.renderpearl.backend.common.BaseGpuTextureView#texture"),
                "Resolve the texture from its view, then obtain VkImage."),

        /** Native VkImage from the final composition texture intercepted at blitFromTexture. */
        /** Private currentImageIndex identifies the acquired swapchain target within the image array. */
        SWAPCHAIN_IMAGE_INDEX("Current swapchain image index",
                List.of("com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface#currentImageIndex"),
                "After acquire, identifies the swapchain image to render this frame."),

        /** Swapchain image array (LongList). */
        SWAPCHAIN_IMAGES("Swapchain image array",
                List.of("com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface#swapchainImages"),
                "Native handles for all swapchain images."),

        /** Swapchain VkFormat. */
        SWAPCHAIN_FORMAT("Swapchain format",
                List.of("com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface#swapchainImageFormat"),
                "Presentation target format used for blit compatibility checks."),

        TEXTURE_IMAGE_HANDLE("VkImage handle",
                List.of("com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture#vkImage"),
                "Native image handle exposed as frame.color / frame.swapchain."),

        // Scene extraction hooks provide world geometry to SceneSnapshot. Each hook identifies one required operation: candidate resolution selects only the first available target, so operations such as isAir and isSolid need separate entries.

        CLIENT_LEVEL("Client world",
                List.of("net.minecraft.client.Minecraft#level"),
                "Resolve ClientLevel from Minecraft, the root of scene extraction."),

        LEVEL_GET_BLOCK_STATE("Read block state",
                List.of("net.minecraft.world.level.Level#getBlockState",
                        "net.minecraft.world.level.BlockGetter#getBlockState"),
                "Read a block state at BlockPos, the source of world geometry."),

        LEVEL_IS_LOADED("Section loaded state",
                List.of("net.minecraft.world.level.Level#isLoaded"),
                "Unloaded sections read as air; check this to avoid artificial boundary geometry."),

        BLOCK_POS("Block coordinates",
                List.of("net.minecraft.core.BlockPos"),
                "Construct a BlockPos; reuse instances across queries instead of allocating each time."),

        BLOCK_STATE_IS_AIR("Air block test",
                List.of("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase#isAir"),
                "Air emits no geometry."),

        BLOCK_STATE_IS_SOLID("Solid block test",
                List.of("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase#isSolid"),
                "Determine adjacent face culling; glass has geometry but must not cull neighboring faces."),

        CAMERA_RENDER_STATE("Camera render state",
                List.of("net.minecraft.client.renderer.state.level.CameraRenderState"),
                "Camera data lives here since 26.3; Camera no longer exposes getPosition/getLookVector."
                        + "pos / xRot / yRot / projectionMatrix / viewRotationMatrix / depthFar。");

        private final String displayName;
        private final List<String> candidates;
        private final String purpose;

        Hookpoint(String displayName, List<String> candidates, String purpose) {
            this.displayName = displayName;
            this.candidates = candidates;
            this.purpose = purpose;
        }

        public String displayName() {
            return tr(displayName);
        }

        /** Candidate class#method targets; a class alone requires only class resolution. */
        public List<String> candidates() {
            return candidates;
        }

        public String purpose() {
            return tr(purpose);
        }

        /** Whether resolution is required for core loader functionality. */
        public boolean isEssential() {
            return this == GPU_DEVICE || this == DEVICE_INFO || this == SURFACE_BACKEND
                    || this == SURFACE_BACKEND_DEVICE || this == DEVICE_HANDLE
                    || this == INSTANCE_HANDLE;
        }

        /** Whether the target is a field rather than a method. */
        public boolean isFieldBinding() {
            // CLIENT_LEVEL is Minecraft.level, a public field rather than a getter; mark it as a field to avoid false partial-resolution failures.
            return this == SURFACE_BACKEND || this == SURFACE_BACKEND_DEVICE
                    || this == CLIENT_LEVEL;
        }
    }

    private final Map<String, String> overrides = new LinkedHashMap<>();
    private final Map<String, String> resolved = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private final boolean verbose;

    public MinecraftMapping() {
        this(false);
    }

    public MinecraftMapping(boolean verbose) {
        this.verbose = verbose;
    }

    /** Explicit target override bypassing candidate probing. */
    public MinecraftMapping override(String hookpointName, String target) {
        if (hookpointName != null && target != null && !target.isBlank()) {
            overrides.put(hookpointName, target.trim());
        }
        return this;
    }

    /**
     * Resolves an integration point.
     * @param hookpoint logical hook
     * @param loader classloader resolving its targets; real integration requires the game loader
     */
    public Optional<ResolvedHook> resolve(Hookpoint hookpoint, ClassLoader loader) {
        String override = overrides.get(hookpoint.name());
        if (override != null) {
            ResolvedHook hook = tryResolve(override, loader);
            if (hook != null) {
                resolved.put(hookpoint.name(), override);
                return Optional.of(hook);
            }
            failures.add(tr("User override ") + override + tr(" failed to load (integration point ") + hookpoint.name() + "）");
        }

        for (String candidate : hookpoint.candidates()) {
            ResolvedHook hook = tryResolve(candidate, loader);
            if (hook != null) {
                resolved.put(hookpoint.name(), candidate);
                if (verbose) {
                    System.out.println(tr("[LuxLoader][MC Mapping] ") + hookpoint.name() + " → " + candidate);
                }
                return Optional.of(hook);
            }
        }

        failures.add(hookpoint.name() + "（" + hookpoint.displayName() + tr(") has no usable target; attempted: ")
                + String.join(" | ", hookpoint.candidates()));
        return Optional.empty();
    }

    /**
     * Convenience resolution through this classpath. Real mod environments may use a distinct game
     * classloader, which should be passed explicitly; this overload mainly serves tests/shared-classpath
     * hosts.
     */
    public Optional<ResolvedHook> resolve(Hookpoint hookpoint) {
        return resolve(hookpoint, MinecraftMapping.class.getClassLoader());
    }

    private ResolvedHook tryResolve(String target, ClassLoader loader) {
        String className = target;
        String methodName = null;
        int hash = target.indexOf('#');
        if (hash >= 0) {
            className = target.substring(0, hash);
            methodName = target.substring(hash + 1);
        }
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            return new ResolvedHook(target, clazz, methodName);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** Resolved mappings. */
    public Map<String, String> resolvedMappings() {
        return Map.copyOf(resolved);
    }

    /** Whether a hook is resolved. */
    public boolean isResolved(Hookpoint hookpoint) {
        return resolved.containsKey(hookpoint.name());
    }

    /** Failure reasons for diagnostics. */
    public List<String> failures() {
        return List.copyOf(failures);
    }

    /** Clears resolution/failure state before probing again. */
    public void reset() {
        resolved.clear();
        failures.clear();
    }

    /** Formats a diagnostic section. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(tr("Resolved integration points (")).append(resolved.size()).append("/")
                .append(Hookpoint.values().length).append("):").append(System.lineSeparator());
        for (Hookpoint hookpoint : Hookpoint.values()) {
            String target = resolved.get(hookpoint.name());
            sb.append("  ").append(hookpoint.name()).append(" = ")
                    .append(target == null ? tr(" (unresolved)") : target)
                    .append(hookpoint.isEssential() ? tr("  [required]") : "")
                    .append(System.lineSeparator());
        }
        if (!failures.isEmpty()) {
            sb.append(tr("Unresolved (")).append(failures.size()).append("):").append(System.lineSeparator());
            for (String failure : failures) {
                sb.append("  ").append(failure).append(System.lineSeparator());
            }
            sb.append(tr("Override targets in mcBridge.overrides using "))
                    .append(tr("\"integration.point\": \"qualified.Class#method\"."))
                    .append(System.lineSeparator());
        }
        return sb.toString();
    }

    /** Resolved hook. */
    public record ResolvedHook(String target, Class<?> type, String methodName) {

        public boolean hasMethod() {
            return methodName != null && !methodName.isBlank();
        }

        /**
         * Finds a named method through classes/interfaces. Targets may also represent fields, so resolution
         * checks both kinds and lets the caller select the required member.
         */
        public Optional<java.lang.reflect.Method> findMethod() {
            if (!hasMethod()) {
                return Optional.empty();
            }
            return findMethodIn(type, methodName, 0);
        }

        /** Finds a named field, including private fields, through the class hierarchy. */
        public Optional<java.lang.reflect.Field> findField() {
            if (!hasMethod()) {
                return Optional.empty();
            }
            return findFieldIn(type, methodName, 0);
        }

        /** At least one method or field is present. */
        public boolean isBindablePresent() {
            return !hasMethod() || findMethod().isPresent() || findField().isPresent();
        }

        /** Whether a field exists. */
        public boolean isFieldPresent() {
            return findField().isPresent();
        }

        /**
         * Reads a field binding, returning empty on failure rather than throwing across the adapter boundary.
         * Method bindings use findMethod().
         */
        public Optional<Object> readField(Object instance) {
            if (instance == null) {
                return Optional.empty();
            }
            return findField().flatMap(field -> {
                try {
                    return Optional.ofNullable(field.get(instance));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return Optional.empty();
                }
            });
        }

        private static Optional<java.lang.reflect.Method> findMethodIn(Class<?> clazz, String name, int depth) {
            if (clazz == null || depth > 4) {
                return Optional.empty();
            }
            for (var method : safeMethods(clazz)) {
                if (method.getName().equals(name)) {
                    method.setAccessible(true);
                    return Optional.of(method);
                }
            }
            Optional<java.lang.reflect.Method> fromInterface = Optional.empty();
            for (Class<?> iface : clazz.getInterfaces()) {
                fromInterface = findMethodIn(iface, name, depth + 1);
                if (fromInterface.isPresent()) {
                    return fromInterface;
                }
            }
            return findMethodIn(clazz.getSuperclass(), name, depth + 1);
        }

        private static Optional<java.lang.reflect.Field> findFieldIn(Class<?> clazz, String name, int depth) {
            if (clazz == null || depth > 4) {
                return Optional.empty();
            }

            // Try getDeclaredField(name) before enumerating all fields. Enumeration resolves unrelated field types and can fail on missing optional game libraries, hiding valid fields such as level. Targeted lookup isolates the requested member's type.
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException e) {
                // Continue searching the superclass.
            } catch (LinkageError | RuntimeException e) {
                // If a field type cannot resolve, scan available fields to retain valid candidates.
            }

            for (var field : safeFields(clazz)) {
                if (field.getName().equals(name)) {
                    field.setAccessible(true);
                    return Optional.of(field);
                }
            }
            return findFieldIn(clazz.getSuperclass(), name, depth + 1);
        }

        /**
         * Method enumeration may throw NoClassDefFoundError when signatures reference unavailable classes.
         * Treat unresolved members as unavailable with diagnostics instead of aborting adapter startup.
         */
        private static java.lang.reflect.Method[] safeMethods(Class<?> clazz) {
            try {
                return clazz.getDeclaredMethods();
            } catch (LinkageError | RuntimeException e) {
                return new java.lang.reflect.Method[0];
            }
        }

        /** Field equivalent of safeMethods. */
        private static java.lang.reflect.Field[] safeFields(Class<?> clazz) {
            try {
                return clazz.getDeclaredFields();
            } catch (LinkageError | RuntimeException e) {
                return new java.lang.reflect.Field[0];
            }
        }

        /** Requires the actual member as well as its class, catching renamed methods before runtime use. */
        public boolean isMethodPresent() {
            return !hasMethod() || findMethod().isPresent();
        }
    }
}
