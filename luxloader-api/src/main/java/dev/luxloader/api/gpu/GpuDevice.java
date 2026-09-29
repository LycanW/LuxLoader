package dev.luxloader.api.gpu;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * GPU device abstraction. A host device wraps Minecraft's {@code VkDevice}, allowing plugin commands
 * to share submission without copies. Alternatively, a plugin can own a device and share images
 * through {@code VK_KHR_external_memory}/{@code VK_KHR_external_semaphore} when it needs dedicated
 * queues or a different queue configuration. <p>Implementations must be thread-safe: UI, diagnostics
 * and hot switching may access them outside Minecraft's render thread.
 */
public interface GpuDevice extends AutoCloseable {

    /** Device capability snapshot. */
    GpuCapabilities capabilities();

    /** Device name, equivalent to capabilities().deviceName(). */
    default String name() {
        return capabilities().deviceName();
    }

    /**
     * Loaded native Vulkan library ({@code vulkan-1.dll} on Windows). Plugins can use it to resolve
     * additional commands through {@code vkGetDeviceProcAddr}. Zero means direct native API access is
     * unavailable.
     */
    long nativeHandle();

    /** Device command recorder. */
    GpuCommands commands();

    /**
     * Requests a queue for plugin work. Prefer dedicated async-compute/transfer families to avoid graphics
     * contention. If none exists, return the graphics queue with {@link GpuQueue#isDedicated()} false and
     * report the fallback.
     * @param hint intended use, e.g. {@code dlss-fg} or {@code ssao}
     */
    GpuQueue requestQueue(String hint);

    /**
     * Create or reuse a cached texture. The host owns its lifetime; identical descriptions reuse the
     * resource, released by close(). Pipelines must not destroy it themselves.
     */
    /**
     * Creates a compute pipeline from caller-supplied SPIR-V. The pass decides whether to load, compile or
     * retrieve its shader from cache; the loader does not read the descriptor path implicitly. The default
     * implementation throws immediately rather than returning an invalid zero handle.
     * @return native pipeline handle
     */
    default long createComputePipeline(ComputePipelineDesc desc, byte[] spirv) {
        throw new UnsupportedOperationException(
                tr("This device cannot create compute pipelines: ") + (desc == null ? "null" : desc.name()));
    }

    /** Create a raster pipeline from vertex and fragment SPIR-V. */
    default long createGraphicsPipeline(GraphicsPipelineDesc desc, byte[] vertexSpirv,
                                        byte[] fragmentSpirv) {
        throw new UnsupportedOperationException("Graphics pipelines are unavailable on this device");
    }
    ImageHandle createImage(ImageDesc desc);

    /** Create or reuse a buffer with the same ownership semantics as createImage. */
    Handle createBuffer(BufferDesc desc);

    /**
     * Imports an external host/device resource, such as shared scene color, depth or motion vectors, for
     * sampling on this device.
     * @param external shared handle, usually {@link ImageHandle.Kind#WIN32_HANDLE}
     * @param desc image description matching the external resource
     * @return imported device resource
     */
    ImageHandle importImage(ImageHandle external, ImageDesc desc);

    /** Export a shared handle for the host or another device. Unsupported exports return a null image handle. */
    ImageHandle exportImage(ImageHandle image);

    /**
     * Create a ray tracing BVH. Unsupported devices return AcceleratorHandle.unsupported(); callers must
     * select a fallback.
     */
    AcceleratorHandle createAccelerator(AccelStructDesc desc);

    /** Simple buffer handle. */
    record Handle(Kind kind, long bits, long size, String label) {
        public enum Kind { VK_BUFFER, WIN32_HANDLE, OPAQUE }

        public Handle {
            Objects.requireNonNull(kind, "kind");
            label = label == null ? "" : label;
        }

        public boolean isNull() {
            return bits == 0L;
        }
    }

    @Override
    void close();
}
