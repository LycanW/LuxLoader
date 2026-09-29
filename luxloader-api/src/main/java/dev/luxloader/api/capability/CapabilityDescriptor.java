package dev.luxloader.api.capability;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A capability's verified availability on this machine. Registrants define identifiers; the loader
 * stores and queries them without vendor-specific logic. Use dot-separated identifiers for prefix
 * queries, e.g. graphics.ray_query or myteam.temporal_reprojection.
 * @param id capability identifier
 * @param level support level
 * @param displayName UI name
 * @param provider registering plugin ID
 * @param detail versions, missing requirements or limitations
 * @param attributes structured additional information
 * @param verifiedAtNanos last verification time from System.nanoTime(); zero means unverified
 */
public record CapabilityDescriptor(
        String id,
        CapabilityLevel level,
        String displayName,
        String provider,
        String detail,
        Map<String, String> attributes,
        long verifiedAtNanos) {

    public CapabilityDescriptor {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException(tr("Capability ID must not be empty"));
        }
        level = level == null ? CapabilityLevel.UNSUPPORTED : level;
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        provider = provider == null ? "" : provider;
        detail = detail == null ? "" : detail;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Create an availability record without details. */
    public static CapabilityDescriptor of(String id, boolean available, String provider) {
        return new CapabilityDescriptor(id, CapabilityLevel.of(available), id, provider, "", Map.of(), 0L);
    }

    public static CapabilityDescriptor of(String id, CapabilityLevel level, String provider) {
        return new CapabilityDescriptor(id, level, id, provider, "", Map.of(), 0L);
    }

    /** Record an unavailable capability and the reason shown to users. */
    public static CapabilityDescriptor unsupported(String id, String provider, String reason) {
        return new CapabilityDescriptor(id, CapabilityLevel.UNSUPPORTED, id, provider, reason, Map.of(), 0L);
    }

    public CapabilityDescriptor withDetail(String detail) {
        return new CapabilityDescriptor(id, level, displayName, provider, detail, attributes, verifiedAtNanos);
    }

    public CapabilityDescriptor withLevel(CapabilityLevel newLevel) {
        return new CapabilityDescriptor(id, newLevel, displayName, provider, detail, attributes, verifiedAtNanos);
    }

    public CapabilityDescriptor withDisplayName(String name) {
        return new CapabilityDescriptor(id, level, name, provider, detail, attributes, verifiedAtNanos);
    }

    public CapabilityDescriptor withAttribute(String key, String value) {
        Map<String, String> next = new java.util.LinkedHashMap<>(attributes);
        next.put(key, value);
        return new CapabilityDescriptor(id, level, displayName, provider, detail, next, verifiedAtNanos);
    }

    public CapabilityDescriptor verifiedNow() {
        return new CapabilityDescriptor(id, level, displayName, provider, detail, attributes, System.nanoTime());
    }

    public boolean isUsable() {
        return level.isUsable();
    }

    /** Read a structured attribute. */
    public java.util.Optional<String> attribute(String key) {
        return java.util.Optional.ofNullable(attributes.get(key));
    }

    /** Read an integer attribute. */
    public int intAttribute(String key, int fallback) {
        String v = attributes.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** One-line diagnostic representation. */
    public String describe() {
        return id + " = " + level + (detail.isEmpty() ? "" : " — " + detail)
                + (provider.isEmpty() ? "" : " [" + provider + "]");
    }

    /**
     * Conventional public capability keys, not built-in implementations. Any plugin may register or query
     * them. This is their sole definition; HostAdapter and other interfaces reference these constants to
     * prevent drift.
     */
    public static final class Ids {
        /** Inline ray queries (VK_KHR_ray_query). */
        public static final String RAY_QUERY = "graphics.ray_query";
        /** Dedicated ray tracing pipelines. */
        public static final String RAY_TRACING_PIPELINE = "graphics.ray_tracing_pipeline";
        /** Acceleration structures. */
        public static final String ACCELERATION_STRUCTURE = "graphics.acceleration_structure";
        /** Descriptor buffers. */
        public static final String DESCRIPTOR_BUFFER = "graphics.descriptor_buffer";
        /** Mesh shaders. */
        public static final String MESH_SHADER = "graphics.mesh_shader";
        /** External memory and semaphore sharing for device interoperability. */
        public static final String EXTERNAL_MEMORY = "graphics.external_memory";
        public static final String EXTERNAL_SEMAPHORE = "graphics.external_semaphore";
        /** Timeline semaphores. */
        public static final String TIMELINE_SEMAPHORE = "graphics.timeline_semaphore";
        /** Variable swapchain dimensions and present timing. */
        public static final String PRESENT_TIMING = "graphics.present_timing";
        /** HDR swapchain (10-bit / HDR10). */
        public static final String HDR_SWAPCHAIN = "graphics.hdr_swapchain";
        /** Independent asynchronous compute queue. */
        public static final String ASYNC_COMPUTE_QUEUE = "graphics.async_compute_queue";
        /** Native library loading through FFM. */
        public static final String NATIVE_BRIDGE = "host.native_bridge";
        /** Whether the observed backend is Vulkan. */
        public static final String VULKAN_BACKEND = "host.vulkan_backend";
        /** Access to the host graphics device. */
        public static final String DEVICE_ACCESSIBLE = "host.gpu_device_accessible";
        /** Final presentation interception, required for upscaling and frame generation. */
        public static final String PRESENT_INTERCEPT = "host.present_intercept";
        /** Host frame graph integration. */
        public static final String FRAME_GRAPH = "host.frame_graph";
        /** Device information queries: backend, extensions and limits. */
        public static final String DEVICE_INFO = "host.device_info";
        /** Reliable host notification of swapchain size changes. */
        public static final String RESIZE_SIGNAL = "host.resize_signal";
        /** Host frame hook availability. */
        public static final String FRAME_HOOK = "host.frame_hook";
        /** Host motion vectors for temporal upscaling. */
        public static final String MOTION_VECTORS_AVAILABLE = "host.motion_vectors";
        /**
         * Host extraction of world geometry into neutral data. Required for complete rendering replacement;
         * without geometry, plugins can only augment existing frames.
         */
        public static final String SCENE_EXTRACTION = "host.scene_extraction";
        /** Whether a plugin can own rendering of the entire frame. */
        public static final String FRAME_OWNERSHIP = "host.frame_ownership";

        private Ids() {
        }

        /** All conventional keys for diagnostic enumeration. */
        public static List<String> all() {
            return List.of(RAY_QUERY, RAY_TRACING_PIPELINE, ACCELERATION_STRUCTURE, DESCRIPTOR_BUFFER,
                    MESH_SHADER, EXTERNAL_MEMORY, EXTERNAL_SEMAPHORE, TIMELINE_SEMAPHORE,
                    PRESENT_TIMING, HDR_SWAPCHAIN, ASYNC_COMPUTE_QUEUE, NATIVE_BRIDGE, VULKAN_BACKEND,
                    DEVICE_ACCESSIBLE, PRESENT_INTERCEPT, FRAME_GRAPH, DEVICE_INFO, RESIZE_SIGNAL,
                    FRAME_HOOK, MOTION_VECTORS_AVAILABLE, SCENE_EXTRACTION, FRAME_OWNERSHIP);
        }

        /** Host capability keys registered by HostAdapter. */
        public static List<String> hostIds() {
            return List.of(VULKAN_BACKEND, DEVICE_ACCESSIBLE, PRESENT_INTERCEPT, FRAME_GRAPH,
                    DEVICE_INFO, RESIZE_SIGNAL, FRAME_HOOK, MOTION_VECTORS_AVAILABLE, NATIVE_BRIDGE,
                    SCENE_EXTRACTION, FRAME_OWNERSHIP);
        }

        /** Graphics device capability keys registered by core device probing. */
        public static List<String> graphicsIds() {
            return List.of(RAY_QUERY, RAY_TRACING_PIPELINE, ACCELERATION_STRUCTURE, DESCRIPTOR_BUFFER,
                    MESH_SHADER, EXTERNAL_MEMORY, EXTERNAL_SEMAPHORE, TIMELINE_SEMAPHORE,
                    PRESENT_TIMING, HDR_SWAPCHAIN, ASYNC_COMPUTE_QUEUE);
        }
    }
}
