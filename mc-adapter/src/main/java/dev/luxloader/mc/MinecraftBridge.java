package dev.luxloader.mc;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.LuxMod;
import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;

import java.util.Optional;

/**
 * Minecraft integration bridge probing version-specific rendering hooks. The local 26.3 client uses
 * unobfuscated renderpearl graphics abstractions and a blaze3d FrameGraphBuilder; inferred
 * blaze3d.vulkan names were incorrect. Require both classes and members, report missing capabilities
 * without aborting startup, and verify mappings against real JARs. Mapping resolution alone does not
 * prove command-stream integration, which is supplied and tested by the mod-loader hooks.
 */
public final class MinecraftBridge {

    /** Capability: active Vulkan backend. */
    public static final String CAP_VULKAN = "host.vulkan_backend";
    /** Capability: graphics device interface access. */
    public static final String CAP_DEVICE = "host.gpu_device_accessible";
    /** Capability: final presentation interception for upscaling/frame generation. */
    public static final String CAP_PRESENT_INTERCEPT = "host.present_intercept";
    /** Capability: host frame graph integration. */
    public static final String CAP_FRAME_GRAPH = "host.frame_graph";
    /** Capability: device information, extensions and limits. */
    public static final String CAP_DEVICE_INFO = "host.device_info";
    /** Capability: swapchain resize detection. */
    public static final String CAP_SURFACE_CONFIGURE = "host.surface_configure";

    private final LuxMod mod;
    private final MinecraftMapping mapping;
    private final ClassLoader gameClassLoader;
    private final boolean verbose;

    private boolean vulkanBackend;
    private boolean glBackendDetected;
    private boolean deviceAccessible;
    private boolean presentInterceptable;
    private boolean frameGraphAvailable;
    private boolean deviceInfoAvailable;
    private boolean surfaceConfigureObservable;
    private boolean probed;

    /**
     * @param mod loader mod metadata
     * @param gameClassLoader loader for Minecraft classes, null to use this class's loader
     * @param verbose whether to log detailed probing
     */
    public MinecraftBridge(LuxMod mod, ClassLoader gameClassLoader, boolean verbose) {
        this.mod = mod;
        this.gameClassLoader = gameClassLoader == null
                ? MinecraftBridge.class.getClassLoader() : gameClassLoader;
        this.mapping = new MinecraftMapping(verbose);
        this.verbose = verbose;
    }

    /** Convenience constructor using this class's loader. */
    public MinecraftBridge(LuxMod mod, boolean verbose) {
        this(mod, null, verbose);
    }

    /** Overrides an integration target from configuration. */
    public MinecraftBridge override(MinecraftMapping.Hookpoint hookpoint, String target) {
        mapping.override(hookpoint.name(), target);
        return this;
    }

    /** Overrides an integration target by logical name. */
    public MinecraftBridge override(String hookpointName, String target) {
        mapping.override(hookpointName, target);
        return this;
    }

    /**
     * Probes safely even without Minecraft; unresolved hooks represent unavailable capabilities rather
     * than exceptions.
     * @return diagnostic summary
     */
    public String probe() {
        mapping.reset();

        // Probe concrete Vulkan/GL backend availability instead of relying on internal GpuDevice implementation names.
        boolean vulkanPresent = mapping.resolve(MinecraftMapping.Hookpoint.BACKEND_VULKAN,
                gameClassLoader).isPresent();
        boolean glPresent = mapping.resolve(MinecraftMapping.Hookpoint.BACKEND_OPENGL,
                gameClassLoader).isPresent();
        this.vulkanBackend = vulkanPresent;
        this.glBackendDetected = glPresent;

        // Required device interface.
        deviceAccessible = resolveMethod(MinecraftMapping.Hookpoint.GPU_DEVICE);
        // Device information for capability negotiation.
        deviceInfoAvailable = mapping.resolve(MinecraftMapping.Hookpoint.DEVICE_INFO,
                gameClassLoader).isPresent();

        // Final composition hook for upscaling/frame generation.
        presentInterceptable = resolveMethod(MinecraftMapping.Hookpoint.BLIT_TO_SCREEN)
                && resolveMethod(MinecraftMapping.Hookpoint.PRESENT);
        // Surface configuration provides authoritative resize signals.
        surfaceConfigureObservable = resolveMethod(MinecraftMapping.Hookpoint.SURFACE_CONFIGURE);
        // Host frame graph.
        frameGraphAvailable = mapping.resolve(MinecraftMapping.Hookpoint.FRAME_GRAPH,
                gameClassLoader).isPresent();

        // Also resolve acquisition for complete diagnostics.
        mapping.resolve(MinecraftMapping.Hookpoint.ACQUIRE_TEXTURE, gameClassLoader);

        probed = true;
        return describe();
    }

    /** Resolves a hook and verifies the method exists. */
    private boolean resolveMethod(MinecraftMapping.Hookpoint hookpoint) {
        Optional<MinecraftMapping.ResolvedHook> hook = mapping.resolve(hookpoint, gameClassLoader);
        if (hook.isEmpty()) {
            return false;
        }
        if (!hook.get().isMethodPresent()) {
            // Distinguish a present class with a missing method from a usable hook.
            return false;
        }
        return true;
    }

    /**
     * Registers probe facts so pipelines can query backend/presentation availability and choose their own
     * fallbacks.
     */
    public void registerCapabilities(java.util.function.Consumer<CapabilityDescriptor> sink) {
        String provider = mod == null ? "luxloader" : mod.id().toString();

        sink.accept(new CapabilityDescriptor(CAP_VULKAN,
                CapabilityLevel.of(vulkanBackend),
                tr("Vulkan backend"), provider,
                vulkanBackend
                        ? tr("Detected renderpearl VulkanBackend; game uses Vulkan")
                        : (glBackendDetected
                        ? tr("VulkanBackend not detected, but GlBackend exists. ")
                        + tr("Mojang marks Vulkan experimental since 26.2 Snapshot 8, ")
                        + tr("with OpenGL as the default, so this may be expected.")
                        : tr("Neither backend implementation was detected: this may not be a 26.3+ client, ")
                        + tr("or integration mappings need updating")),
                java.util.Map.of("backendName", backendName()),
                System.nanoTime()));

        sink.accept(new CapabilityDescriptor(CAP_DEVICE,
                CapabilityLevel.of(deviceAccessible),
                tr("Graphics device access"), provider,
                deviceAccessible
                        ? tr("GpuDevice interface located; pipelines can reuse the host device and resources")
                        : tr("GpuDevice interface not located; only offscreen rendering is available"),
                java.util.Map.of(), System.nanoTime()));

        sink.accept(new CapabilityDescriptor(CAP_PRESENT_INTERCEPT,
                CapabilityLevel.of(presentInterceptable),
                tr("Pre-presentation interception"), provider,
                presentInterceptable
                        ? tr("Located blitFromTexture and present: ")
                        + tr("upscaling, frame generation and ray tracing composition can integrate here")
                        : tr("Pre-presentation integration unavailable; pipelines cannot own final output, as required for upscaling"),
                java.util.Map.of(), System.nanoTime()));

        sink.accept(new CapabilityDescriptor(CAP_FRAME_GRAPH,
                CapabilityLevel.of(frameGraphAvailable),
                tr("Host frame graph"), provider,
                frameGraphAvailable
                        ? tr("Minecraft 26.3 supplies FrameGraphBuilder; integrate pipeline passes into it ")
                        + tr("so the host manages resource lifetimes and dependencies")
                        : tr("Host frame graph not located; pipelines must manage pass order and resources"),
                java.util.Map.of(), System.nanoTime()));

        sink.accept(new CapabilityDescriptor(CAP_DEVICE_INFO,
                CapabilityLevel.of(deviceInfoAvailable),
                tr("Device information queries"), provider,
                deviceInfoAvailable
                        ? tr("Backend, vendor, extensions, limits and features can be queried; ")
                        + tr("capability negotiation need not infer support from version numbers")
                        : tr("Device information unavailable; capability negotiation requires loader-side Vulkan probing"),
                java.util.Map.of(), System.nanoTime()));

        sink.accept(new CapabilityDescriptor(CAP_SURFACE_CONFIGURE,
                CapabilityLevel.of(surfaceConfigureObservable),
                tr("Swapchain configuration observation"), provider,
                surfaceConfigureObservable
                        ? tr("configure calls can be observed for reliable window resize notifications")
                        : tr("Swapchain changes cannot be observed; the host must notify resolution changes separately"),
                java.util.Map.of(), System.nanoTime()));
    }

    /** Probe summary for diagnostic reports. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(tr("Minecraft integration probe:")).append(System.lineSeparator());
        sb.append(tr("  Backend: ")).append(backendName()).append(System.lineSeparator());
        sb.append(tr("  Graphics device interface: ")).append(deviceAccessible ? tr("Available") : tr("Unavailable"))
                .append(System.lineSeparator());
        sb.append(tr("  Device information: ")).append(deviceInfoAvailable ? tr("Available") : tr("Unavailable"))
                .append(System.lineSeparator());
        sb.append(tr("  Pre-presentation interception: ")).append(presentInterceptable ? tr("Available") : tr("Unavailable"))
                .append(System.lineSeparator());
        sb.append(tr("  Swapchain configuration observation: ")).append(surfaceConfigureObservable ? tr("Available") : tr("Unavailable"))
                .append(System.lineSeparator());
        sb.append(tr("  Host frame graph: ")).append(frameGraphAvailable ? tr("Available") : tr("Unavailable"))
                .append(System.lineSeparator());
        sb.append(System.lineSeparator()).append(mapping.describe());
        return sb.toString();
    }

    /** Backend description. */
    private String backendName() {
        if (vulkanBackend && glBackendDetected) {
            return tr("Vulkan and OpenGL implementations exist (confirm the active backend with DeviceInfo.backendName())");
        }
        if (vulkanBackend) {
            return "Vulkan";
        }
        if (glBackendDetected) {
            return "OpenGL";
        }
        return tr("Not detected (possibly not a 26.3+ client)");
    }

    // State queries.

    public boolean isVulkanBackend() {
        return vulkanBackend;
    }

    public boolean isGlBackendDetected() {
        return glBackendDetected;
    }

    public boolean isDeviceAccessible() {
        return deviceAccessible;
    }

    public boolean isPresentInterceptable() {
        return presentInterceptable;
    }

    public boolean isFrameGraphAvailable() {
        return frameGraphAvailable;
    }

    public boolean isDeviceInfoAvailable() {
        return deviceInfoAvailable;
    }

    public boolean isSurfaceConfigureObservable() {
        return surfaceConfigureObservable;
    }

    public boolean isProbed() {
        return probed;
    }

    public MinecraftMapping mapping() {
        return mapping;
    }

    public ClassLoader gameClassLoader() {
        return gameClassLoader;
    }

    /** Resolved hooks. */
    public Optional<MinecraftMapping.ResolvedHook> hook(MinecraftMapping.Hookpoint hookpoint) {
        return mapping.resolve(hookpoint, gameClassLoader);
    }
}
