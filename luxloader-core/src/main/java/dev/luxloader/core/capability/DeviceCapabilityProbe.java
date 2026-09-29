package dev.luxloader.core.capability;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuDevice;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Map device features to semantic capabilities so pipelines need not repeat extension checks. Ray
 * tracing and descriptor buffers are not Vulkan 1.4 core features; verify extensions instead of
 * inferring availability from the API version.
 */
public final class DeviceCapabilityProbe {

    /** Minimum extension set for ray tracing pipelines. */
    private static final String[] RT_COMMON = {
            "VK_KHR_acceleration_structure",
            "VK_KHR_deferred_host_operations",
            "VK_KHR_buffer_device_address"
    };

    private DeviceCapabilityProbe() {
    }

    /** Probe into a registry. A null device records unavailable results until device initialization completes. */
    public static void probeInto(CapabilityRegistryImpl registry, GpuDevice device) {
        if (device == null) {
            registry.forceRegister(CapabilityDescriptor.unsupported(
                    CapabilityDescriptor.Ids.VULKAN_BACKEND, "device-probe",
                    tr("Device not ready: the host may not be using Vulkan, or device creation failed")));
            return;
        }

        GpuCapabilities caps = device.capabilities();
        String provider = "device-probe";

        // Register individual extensions so missing requirements remain visible.
        for (String extension : caps.extensions()) {
            registry.extension(extension, true);
        }

        // Semantic capabilities.
        registerExtensionBacked(registry, provider, caps,
                CapabilityDescriptor.Ids.ACCELERATION_STRUCTURE, "VK_KHR_acceleration_structure",
                tr("Ray tracing acceleration structure (BVH) construction is available"));
        registerExtensionBacked(registry, provider, caps,
                CapabilityDescriptor.Ids.RAY_QUERY, "VK_KHR_ray_query",
                tr("Inline ray queries are available in shaders"));
        registerExtensionBacked(registry, provider, caps,
                CapabilityDescriptor.Ids.RAY_TRACING_PIPELINE, "VK_KHR_ray_tracing_pipeline",
                tr("Dedicated ray tracing pipelines (raygen/hit/miss) are available"));
        registerExtensionBacked(registry, provider, caps,
                CapabilityDescriptor.Ids.DESCRIPTOR_BUFFER, "VK_EXT_descriptor_buffer",
                tr("Descriptor buffers can reduce CPU descriptor overhead"));
        registerExtensionBacked(registry, provider, caps,
                CapabilityDescriptor.Ids.MESH_SHADER, "VK_EXT_mesh_shader",
                tr("Mesh shaders are available"));
        registerExtensionBacked(registry, provider, caps,
                CapabilityDescriptor.Ids.EXTERNAL_MEMORY, "VK_KHR_external_memory",
                tr("GPU memory can be shared across devices/processes"));
        registerExtensionBacked(registry, provider, caps,
                CapabilityDescriptor.Ids.EXTERNAL_SEMAPHORE, "VK_KHR_external_semaphore",
                tr("Synchronization semaphores can be shared across devices"));

        // Timeline semaphores are core since Vulkan 1.2 and an extension on earlier versions.
        boolean timeline = caps.apiAtLeast(1, 2) || caps.supports("VK_KHR_timeline_semaphore");
        registry.forceRegister(new CapabilityDescriptor(
                CapabilityDescriptor.Ids.TIMELINE_SEMAPHORE, CapabilityLevel.of(timeline),
                tr("Timeline semaphores"), provider,
                timeline ? tr("Available (Vulkan ") + caps.apiVersionString() + "）"
                        : tr("Unavailable: requires Vulkan 1.2+ or VK_KHR_timeline_semaphore"),
                Map.of(), System.nanoTime()));

        boolean presentTiming = caps.supports("VK_GOOGLE_display_timing")
                || caps.supports("VK_EXT_present_timing");
        registry.forceRegister(new CapabilityDescriptor(
                CapabilityDescriptor.Ids.PRESENT_TIMING, CapabilityLevel.of(presentTiming),
                tr("Presentation timing"), provider,
                presentTiming ? tr("Driver reports precise presentation timing for generated-frame pacing")
                        : tr("Presentation timing extension unavailable; frame generation must estimate from refresh rate"),
                Map.of(), System.nanoTime()));

        // HDR requires swapchain color-space support from VK_EXT_swapchain_colorspace. VK_KHR_swapchain alone proves only presentation availability and must never imply HDR support.
        boolean hdr = caps.supports("VK_EXT_swapchain_colorspace");
        registry.forceRegister(new CapabilityDescriptor(
                CapabilityDescriptor.Ids.HDR_SWAPCHAIN, CapabilityLevel.of(hdr),
                tr("HDR swapchain"), provider,
                hdr ? tr("VK_EXT_swapchain_colorspace permits querying and selecting HDR color spaces")
                        : tr("Without VK_EXT_swapchain_colorspace, HDR swapchain support ")
                        + tr("cannot be confirmed (a swapchain alone does not imply HDR support)"),
                Map.of(), System.nanoTime()));

        // A swapchain is required for most presentation paths.
        registerExtensionBacked(registry, provider, caps,
                "graphics.swapchain", "VK_KHR_swapchain",
                tr("Swapchain creation is available for presentation"));

        // Ray tracing requires the complete extension set.
        registerRayTracing(registry, provider, caps);

        // An attached device establishes the Vulkan backend.
        registry.forceRegister(new CapabilityDescriptor(
                CapabilityDescriptor.Ids.VULKAN_BACKEND, CapabilityLevel.NATIVE,
                tr("Vulkan backend"), provider,
                tr("Attached Vulkan device: ") + caps.deviceName() + "（Vulkan " + caps.apiVersionString() + "）",
                Map.of("deviceName", caps.deviceName(),
                        "vendor", caps.vendor().key(),
                        "apiVersion", caps.apiVersionString(),
                        "vramBytes", Long.toString(caps.deviceMemoryBytes())),
                System.nanoTime()));

        // Native bridge: check JDK FFM availability.
        boolean nativeBridge = Runtime.version().feature() >= 22;
        registry.forceRegister(new CapabilityDescriptor(
                CapabilityDescriptor.Ids.NATIVE_BRIDGE, CapabilityLevel.of(nativeBridge),
                tr("Native library bridge"), "runtime-probe",
                nativeBridge ? "JDK " + Runtime.version().feature() + tr(" supports FFM for loading vendor native libraries")
                        : "JDK " + Runtime.version().feature() + tr(" is too old; FFM requires JDK 22+"),
                Map.of(), System.nanoTime()));
    }

    /**
     * Register asynchronous queue availability separately. It depends on actual queue families and cannot
     * be inferred from extensions; avoid device queries in probeInto.
     */
    public static void registerQueueCapabilities(CapabilityRegistryImpl registry, boolean asyncCompute,
                                                 boolean dedicatedTransfer) {
        registry.forceRegister(new CapabilityDescriptor(
                CapabilityDescriptor.Ids.ASYNC_COMPUTE_QUEUE, CapabilityLevel.of(asyncCompute),
                tr("Independent asynchronous compute queue"), "device-probe",
                asyncCompute ? tr("An independent compute queue can execute alongside graphics")
                        : tr("Compute shares the graphics queue, limiting parallel execution"),
                Map.of(), System.nanoTime()));
        registry.forceRegister(new CapabilityDescriptor(
                "graphics.async_transfer", CapabilityLevel.of(dedicatedTransfer),
                tr("Dedicated transfer queue"), "device-probe",
                dedicatedTransfer ? tr("A dedicated transfer queue handles uploads independently of graphics")
                        : tr("No dedicated transfer queue"),
                Map.of(), System.nanoTime()));
    }

    private static void registerExtensionBacked(CapabilityRegistryImpl registry, String provider,
                                                GpuCapabilities caps, String id, String extension,
                                                String description) {
        boolean supported = caps.supports(extension);
        registry.forceRegister(new CapabilityDescriptor(id, CapabilityLevel.of(supported),
                id, provider,
                supported ? description + "（" + extension + "）"
                        : tr("Unavailable: device does not expose ") + extension,
                Map.of("extension", extension),
                System.nanoTime()));
    }

    /**
     * Combined ray tracing support: FULL requires acceleration structures, ray queries and dedicated
     * pipelines. NATIVE supports an available tracing path. Acceleration structures alone are PARTIAL and
     * cannot emit rays. Missing base extensions means UNSUPPORTED.
     */
    private static void registerRayTracing(CapabilityRegistryImpl registry, String provider,
                                           GpuCapabilities caps) {
        Set<String> missing = new LinkedHashSet<>();
        for (String extension : RT_COMMON) {
            if (!caps.supports(extension)) {
                missing.add(extension);
            }
        }
        boolean hasRayQuery = caps.supports("VK_KHR_ray_query");
        boolean hasPipeline = caps.supports("VK_KHR_ray_tracing_pipeline");

        CapabilityLevel level;
        String detail;
        if (!missing.isEmpty()) {
            level = CapabilityLevel.UNSUPPORTED;
            detail = tr("Missing base extensions: ") + String.join(", ", missing);
        } else if (hasRayQuery && hasPipeline) {
            level = CapabilityLevel.FULL;
            detail = tr("Acceleration structures, inline ray queries and dedicated ray tracing pipelines are available");
        } else if (hasRayQuery) {
            level = CapabilityLevel.NATIVE;
            detail = tr("Acceleration structures and ray queries are available; no dedicated ray tracing pipeline extension. ")
                    + tr("Ray queries are recommended for geometry-heavy scenes such as Minecraft");
        } else if (hasPipeline) {
            level = CapabilityLevel.NATIVE;
            detail = tr("Acceleration structures and ray tracing pipelines are available; no ray query extension");
        } else {
            level = CapabilityLevel.PARTIAL;
            detail = tr("Acceleration structures exist but neither ray queries nor ray tracing pipelines can emit rays");
        }

        registry.forceRegister(new CapabilityDescriptor(
                "graphics.ray_tracing", level, tr("Hardware ray tracing"), provider, detail,
                Map.of("rayQuery", String.valueOf(hasRayQuery),
                        "rayTracingPipeline", String.valueOf(hasPipeline),
                        "missingExtensions", missing.isEmpty() ? tr("DeviceCapabilityProbe.f4b39d9b45", "(none)") : String.join(",", missing)),
                System.nanoTime()));

        // Keep the graphics.ray_query compatibility alias consistent with the independent registration above.
        if (!caps.supportsRayTracing()) {
            registry.forceRegister(CapabilityDescriptor.unsupported(
                    "graphics.ray_tracing.usable", provider,
                    tr("Device lacks complete ray tracing capability; pipelines should use raster fallback")));
        } else {
            registry.forceRegister(new CapabilityDescriptor(
                    "graphics.ray_tracing.usable", CapabilityLevel.NATIVE,
                    tr("Ray tracing available"), provider, tr("Minimum extensions for emitting rays are available"),
                    Map.of(), System.nanoTime()));
        }
    }
}
