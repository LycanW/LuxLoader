package dev.luxloader.api.pipeline;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.gpu.AccelGeometry;
import dev.luxloader.api.gpu.AccelInstance;
import dev.luxloader.api.gpu.AccelStructDesc;
import dev.luxloader.api.gpu.AcceleratorHandle;
import dev.luxloader.api.gpu.BufferDesc;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.gpu.ImageDesc;
import dev.luxloader.api.gpu.ImageHandle;
import dev.luxloader.api.gpu.ImageViewDesc;

/**
 * Scoped resource creation. Resources from {@code ctx.resources(pipeline)} belong to that pipeline and
 * are released on destruction; pass resources belong to the pass and are released on graph rebuild.
 * Host color/depth/motion/swapchain resources are borrowed and must never be destroyed through this
 * API. Descriptions are cached, so repeated requests for the same image reuse it.
 */
public interface GpuResourceProvider {

    enum Ownership {
        OWNED,
        BORROWED,
        SHARED,
        UNKNOWN
    }

    /** Generic image facts available to optional observers; this does not transfer resource ownership. */
    record ImageInfo(ImageDesc description, Ownership ownership, String ownerId, long contentVersion) {
        public ImageInfo {
            java.util.Objects.requireNonNull(description, "description");
            ownership = ownership == null ? Ownership.UNKNOWN : ownership;
            ownerId = ownerId == null ? "" : ownerId;
            if (contentVersion < 0) {
                throw new IllegalArgumentException("contentVersion must not be negative");
            }
        }
    }

    /** Describes an image allocated by this provider, if it can prove the handle's identity and facts. */
    default java.util.Optional<ImageInfo> imageInfo(ImageHandle image) {
        return java.util.Optional.empty();
    }

    /**
     * Create or reuse a texture; identical ImageDesc values return the same resource and may be requested
     * each frame.
     */
    ImageHandle image(ImageDesc desc);

    /**
     * Borrow a VkImageView for an image known to this provider. This is for
     * SDKs that accept native Vulkan images. The view belongs to the provider:
     * do not destroy or retain it across pipeline shutdown or resource reload.
     * A non-Vulkan provider can reject this operation.
     */
    default long nativeImageView(ImageHandle image, ImageViewDesc view) {
        throw new UnsupportedOperationException("Native Vulkan image views are unavailable");
    }

    /**
     * Creates or retrieves an image shareable across devices/processes. Implementations add
     * external-memory creation flags and export a Win32 handle through {@link #exportHandle(ImageHandle)}
     * for plugins using an independent Vulkan device.
     */
    ImageHandle sharedImage(ImageDesc desc);

    /** Import an externally shared image into this device. */
    ImageHandle importImage(dev.luxloader.api.gpu.ImageHandle external, ImageDesc desc);

    /** Export a shared image handle for the host or another process. */
    ImageHandle exportHandle(ImageHandle image);

    /** Create or reuse a buffer. */
    GpuDevice.Handle buffer(BufferDesc desc);

    /**
     * Uploads buffer data through staging and copy commands rather than directly mapping device memory.
     * Records commands without blocking the CPU.
     * @param destination target buffer
     * @param offset byte offset
     * @param data source data
     */
    void upload(GpuDevice.Handle destination, long offset, java.nio.ByteBuffer data);

    /**
     * Asynchronous buffer readback for GPU statistics or BVH compacted sizes. Do not wait here; the loader
     * invokes the callback when ready.
     * @param source source buffer
     * @param offset byte offset
     * @param size byte count
     * @param callback callback on the render thread
     */
    void readbackAsync(GpuDevice.Handle source, long offset, long size,
                       java.util.function.Consumer<java.nio.ByteBuffer> callback);

    /**
     * Create a ray tracing acceleration structure. Unsupported devices return an unsupported handle;
     * callers must fall back without aborting the pipeline.
     */
    AcceleratorHandle accelerator(AccelStructDesc desc);

    /**
     * Builds or rebuilds a triangle BLAS. {@link #accelerator} allocates storage and the object; this step
     * supplies geometry, allowing changed terrain to rebuild without reallocating. This operation submits
     * and waits synchronously, so avoid it in a per-frame hot path unless rebuilding is necessary (use
     * {@link AccelStructDesc#dynamicBlas}). The default throws UnsupportedOperationException; check {@link
     * AcceleratorHandle#supported()} first.
     * @param blas BOTTOM_LEVEL handle created by accelerator
     * @param geometry triangle geometry
     */
    default void buildAccelerator(AcceleratorHandle blas, AccelGeometry geometry) {
        throw new UnsupportedOperationException(
                tr("This provider cannot build bottom-level acceleration structures; check accelerator(...).supported() first"));
    }

    /**
     * Builds or rebuilds a TLAS from instances of previously built BLAS objects. An empty list is valid
     * and represents no hittable geometry.
     * @param tlas TOP_LEVEL handle created by accelerator
     * @param instances instances, no more than the declared instanceCount
     */
    default void buildTopLevel(AcceleratorHandle tlas, java.util.List<AccelInstance> instances) {
        throw new UnsupportedOperationException(
                tr("This provider cannot build top-level acceleration structures; check accelerator(...).supported() first"));
    }

    /** Record a BLAS build in the caller's command buffer, without submitting or waiting.
     * Input uploads must precede this call. Resources must remain alive until GPU completion.
     * The implementation orders prior reads, the build and subsequent ray queries on this queue. */
    default void recordBuildAccelerator(dev.luxloader.api.gpu.GpuCommands.CommandBuffer commands,
                                         AcceleratorHandle blas, AccelGeometry geometry) {
        throw new UnsupportedOperationException("Recorded acceleration structure builds unavailable");
    }

    /** Record a TLAS build after its BLAS builds, with a snapshot of the supplied instances. */
    default void recordBuildTopLevel(dev.luxloader.api.gpu.GpuCommands.CommandBuffer commands,
                                      AcceleratorHandle tlas, java.util.List<AccelInstance> instances) {
        throw new UnsupportedOperationException("Recorded top level builds unavailable");
    }

    /**
     * Update BLAS geometry or TLAS instances.
     * @param handle target structure
     * @param desc new description within the originally declared capacity
     */
    void updateAccelerator(AcceleratorHandle handle, AccelStructDesc desc);

    /**
     * Current GPU memory consumption in bytes. Allocation increments, release decrements and closeAll
     * clears the total. This is live usage for budget checks, not cumulative allocation; counting
     * historical allocations would incorrectly downgrade long-running pipelines.
     */
    long usedVramBytes();

    /** GPU memory budget in bytes; zero means unknown. */
    long vramBudgetBytes();

    /** Whether the budget is exceeded; pipelines may reduce quality or disable expensive optional features. */
    default boolean overBudget() {
        long budget = vramBudgetBytes();
        return budget > 0 && usedVramBytes() > budget;
    }

    /** Release a previously created image immediately; normally scope teardown handles cleanup. */
    void release(ImageHandle image);

    /** Release a buffer immediately. */
    void release(GpuDevice.Handle buffer);

    /** Release an acceleration structure immediately. */
    void release(AcceleratorHandle accel);
}
