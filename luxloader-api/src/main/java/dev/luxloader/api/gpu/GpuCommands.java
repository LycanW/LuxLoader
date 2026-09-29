package dev.luxloader.api.gpu;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Command recording and submission. LuxLoader owns recording, submission and synchronization for both
 * host-compatible rendering (consume the game's world output) and plugin-owned scene rendering. <p>The
 * host always owns the swapchain and presentation timing. The loader neither creates a swapchain nor
 * calls {@code vkQueuePresentKHR}. <p>Each pass records with {@link #begin} and {@link
 * CommandBuffer#end}. The loader calls {@link #flush} after recording the entire frame, centralizing
 * submission and synchronization.
 */
public interface GpuCommands {

    /** Whether standalone preparation can be submitted before the host freezes its world plan. */
    default boolean supportsPreparationSubmission() { return false; }

    /**
     * Submit and confirm completion of pending preparation commands before selecting a world plan.
     * Illegal inside a host-owned frame encoder. Intended for resource generation publication, not
     * ordinary per-frame updates. Returning means GPU completion was proved, not merely recorded.
     */
    default void completePreparation(GpuQueue queue) {
        throw new UnsupportedOperationException("Standalone preparation submission unavailable");
    }

    /** CPU-visible bytes copied from mip zero, layer zero of a single-sample color image. */
    record ImageReadback(GpuFormat format, int width, int height, int rowStride,
                         String byteOrder, String payloadLayout, byte[] data) {
        public ImageReadback {
            Objects.requireNonNull(format, "format");
            if (width <= 0 || height <= 0 || rowStride <= 0) {
                throw new IllegalArgumentException("Invalid image readback dimensions");
            }
            byteOrder = Objects.requireNonNull(byteOrder, "byteOrder");
            payloadLayout = Objects.requireNonNull(payloadLayout, "payloadLayout");
            data = data == null ? new byte[0] : data.clone();
            if ((long) rowStride * height != data.length) {
                throw new IllegalArgumentException("Image payload length does not match its row stride");
            }
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    /**
     * Records an asynchronous, tightly packed readback of mip zero/layer zero. The source remains owned by
     * its original provider; callback delivery means the GPU completion signal has fired and staging has
     * been mapped. Implementations must preserve the image's tracked layout and must not wait for the GPU.
     * @return false when this backend cannot provide safe asynchronous readback for the source
     */
    default boolean readImageAsync(ImageHandle image, dev.luxloader.api.gpu.ImageDesc description,
                                   Consumer<ImageReadback> completed, Consumer<Throwable> failed) {
        return false;
    }

    /** Current device-tracked image layout, or UNKNOWN when the backend cannot prove it. */
    default String imageLayout(ImageHandle image) {
        return "UNKNOWN";
    }

    /** Borrowed CPU bytes, copied when updateBuffers records the batch. Do not mutate
     * data until recording returns. Destination requires TRANSFER_DST; sizes are in bytes.
     */
    record BufferUpdate(GpuDevice.Handle buffer, long offset, byte[] data) {
        public BufferUpdate {
            if (buffer == null || buffer.isNull() || offset < 0 || (offset & 3) != 0
                    || data == null || data.length == 0 || (data.length & 3) != 0) {
                throw new IllegalArgumentException("Invalid buffer update");
            }
            Math.addExact(offset, data.length);
        }
    }

    /**
     * Starts recording a command buffer. Each {@code GpuCommands} instance permits only one unfinished
     * recording at a time; use separate instances for different queues when recording in parallel.
     * @param label debug name shown by validation layers
     */
    CommandBuffer begin(String label);

    /** GPU timeline semaphore ready for submission. */
    GpuSemaphore createSemaphore(String label);

    /**
     * Submits commands to a queue.
     * @param queue target queue
     * @param buffers command buffers to submit
     * @param wait semaphores to wait on
     * @param signal semaphores to signal after submission
     */
    void submit(GpuQueue queue, java.util.List<CommandBuffer> buffers,
                java.util.List<GpuSemaphore> wait, java.util.List<GpuSemaphore> signal);

    /**
     * Submits all command buffers recorded since the previous flush. Passes choose how many recordings to
     * create; the loader flushes after the full frame to centralize barrier and queue grouping decisions.
     * The implementation recycles submitted buffers and clears the pending set.
     * @param queue target queue
     * @param wait semaphores to wait on
     * @param signal semaphores to signal
     * @return number of submitted buffers, or zero for an empty frame
     */
    default int flush(GpuQueue queue, java.util.List<GpuSemaphore> wait,
                      java.util.List<GpuSemaphore> signal) {
        throw new UnsupportedOperationException(
                tr("This GpuCommands implementation cannot collect command buffers automatically; call submit(...) explicitly"));
    }

    /** Wait for queue idle before safely destroying resources during pipeline switches. */
    void waitIdle(GpuQueue queue);

    /**
     * Wait for every queue of this device before replacing resources referenced
     * by a previous frame. A queue-specific wait is insufficient when a frame
     * graph can route a pass to a different queue family.
     */
    default void waitIdleAll() {
        throw new UnsupportedOperationException("Device-wide idle wait is unavailable");
    }

    /** Whether this frame accepts GPU timestamp scopes. Optional diagnostic work
     * can use this to avoid running on frames where its timing would be discarded.
     * False also covers backends without GPU timestamp support.
     */
    default boolean timestampSampleDue() { return false; }

    /** Insert a GPU timing interval; implementations may ignore it when timing is unnecessary. */
    default void beginTimestamp(String label) {
    }

    default void endTimestamp(String label) {
    }

    /** Command buffer; recording methods must be called before end(). */
    interface CommandBuffer {

        /**
         * Record vendor SDK commands inside this buffer's existing submission.
         * FrameGraph resource reads/writes and layout transitions must be declared
         * before this callback. It is only legal outside a raster render pass.
         * The borrowed handles are valid for the callback's duration only.
         */
        default CommandBuffer recordNativeVulkan(java.util.function.Consumer<NativeVulkanContext> record) {
            throw new UnsupportedOperationException("Native Vulkan recording is unavailable");
        }

        /** Transition an external or prior-pass texture into the layout required by beginRenderPass. */
        CommandBuffer transition(ImageHandle image, ImageViewDesc view, Access from, Access to);

        /**
         * Scales/blits one texture into another, typically presenting pipeline output at the display
         * resolution. The loader handles source {@code TRANSFER_SRC} and target {@code TRANSFER_DST}
         * transitions.
         * @param source source image
         * @param sourceView optional source view
         * @param sourceWidth active source width; zero uses the image width
         * @param sourceHeight active source height
         * @param target destination image
         * @param targetView optional destination view
         * @param targetWidth destination width
         * @param targetHeight destination height
         */
        CommandBuffer blitImage(ImageHandle source, ImageViewDesc sourceView,
                                int sourceWidth, int sourceHeight,
                                ImageHandle target, ImageViewDesc targetView,
                                int targetWidth, int targetHeight);

        /** Initialize a texture by clear or copy, for example to reset history. */
        CommandBuffer clearColor(ImageHandle image, ImageViewDesc view, float r, float g, float b, float a);

        /**
         * Begin a raster pass with color and optional depth attachments.
         * The Vulkan backend uses dynamic rendering. The caller must supply a
         * positive viewport and end the pass before ending the command buffer.
         * {@code LOAD} preserves earlier pixels; {@code CLEAR} discards and clears them.
         */
        CommandBuffer beginRenderPass(RenderPassDesc pass);

        /** Bind a compute pipeline. */
        CommandBuffer bindComputePipeline(ComputePipelineDesc pipeline);

        /** Bind a raster pipeline inside an active render pass. */
        default CommandBuffer bindGraphicsPipeline(GraphicsPipelineDesc pipeline) {
            throw new UnsupportedOperationException("Graphics commands are unavailable");
        }

        /** Bind one interleaved vertex buffer. */
        default CommandBuffer bindVertexBuffer(GpuDevice.Handle buffer, long offset) {
            throw new UnsupportedOperationException("Graphics commands are unavailable");
        }

        /** Bind a 32-bit index buffer. */
        default CommandBuffer bindIndexBuffer(GpuDevice.Handle buffer, long offset) {
            throw new UnsupportedOperationException("Graphics commands are unavailable");
        }

        /** Draw vertices using the active raster pipeline. */
        default CommandBuffer draw(int vertexCount, int instanceCount, int firstVertex,
                                   int firstInstance) {
            throw new UnsupportedOperationException("Graphics commands are unavailable");
        }

        /** Draw indexed vertices using the active raster pipeline. */
        default CommandBuffer drawIndexed(int indexCount, int instanceCount, int firstIndex,
                                          int vertexOffset, int firstInstance) {
            throw new UnsupportedOperationException("Graphics commands are unavailable");
        }

        /**
         * Binds an image descriptor. Sampled views preserve existing pixels; storage views are outputs whose
         * old contents may be discarded.
         * @param name semantic name declared in {@link ComputePipelineDesc.Binding#name()}
         * @param image image
         * @param view image view
         */
        CommandBuffer writeImage(String name, ImageHandle image, ImageViewDesc view);

        /** Bind every element of a fixed-size sampled-image descriptor array.
         * Non-uniform indexing features must be requested by the plugin when used.
         */
        default CommandBuffer writeImages(String name, java.util.List<ImageHandle> images,
                                         java.util.List<ImageViewDesc> views) {
            throw new UnsupportedOperationException("Image arrays are unavailable");
        }

        /** Bind a buffer resource. */
        CommandBuffer writeBuffer(String name, GpuDevice.Handle buffer, long offset, long size);

        /** Order prior compute storage-buffer reads/writes before subsequent compute
         * reads/writes on the same queue. This is a GPU dependency, not a CPU wait.
         * Required when a shader-produced queue is consumed or reused by another dispatch.
         */
        default CommandBuffer computeBufferBarrier() {
            throw new UnsupportedOperationException("Compute buffer dependencies are unavailable");
        }

        /** Record a small buffer update, ordered with surrounding GPU work.
         * Destination must have TRANSFER_DST usage. Offset and byte length must
         * be multiples of four; length is 4..65536. Data is copied while recording.
         * This avoids overwriting mapped per-frame constants still in flight.
         */
        default CommandBuffer updateBuffer(GpuDevice.Handle buffer, long offset, byte[] data) {
            throw new UnsupportedOperationException("Recorded buffer updates are unavailable");
        }

        /** Record ordered uploads of arbitrary size. Later overlapping writes win.
         * All bytes are copied during this call. Backends may batch synchronization
         * and split large transfers without forcing a queue submission or CPU wait.
         */
        default CommandBuffer updateBuffers(java.util.List<BufferUpdate> updates) {
            for (var update : updates) {
                for (int begin = 0; begin < update.data().length;) {
                    int length = Math.min(65536, update.data().length - begin);
                    updateBuffer(update.buffer(), update.offset() + begin,
                            java.util.Arrays.copyOfRange(update.data(), begin, begin + length));
                    begin += length;
                }
            }
            return this;
        }

        /** Ordered GPU copy between distinct buffers with TRANSFER_SRC / TRANSFER_DST
         * usage. Ranges are positive, four-byte aligned and must fit both buffers.
         * Both handles must remain alive until the recorded work completes.
         */
        default CommandBuffer copyBuffer(GpuDevice.Handle source, long sourceOffset,
                                         GpuDevice.Handle destination, long destinationOffset, long bytes) {
            throw new UnsupportedOperationException("Recorded buffer copies are unavailable");
        }

        /** Bind an acceleration structure. */
        CommandBuffer writeAccelStruct(String name, AcceleratorHandle accel);

        /** Write push constants. */
        CommandBuffer pushConstants(byte[] data);

        /** Dispatch compute workgroups. */
        CommandBuffer dispatch(int groupsX, int groupsY, int groupsZ);

        /**
         * Indirect dispatch with arguments from a GPU buffer. Async reprojection/spacewarp can use this when
         * GPU visibility results determine the dispatch dimensions.
         */
        CommandBuffer dispatchIndirect(GpuDevice.Handle args, long offset);

        /**
         * Writes a timestamp for GPU frame timing. The default implementation does nothing when timing is
         * unsupported; it must not fabricate measurements. The loader subtracts consecutive frame timestamps
         * and multiplies by {@code GpuCapabilities#timestampPeriod} to obtain nanoseconds. Call this on the
         * frame's final GPU work to measure the whole frame.
         */
        default CommandBuffer writeTimestamp() {
            return this;
        }

        /** End the render pass. */
        CommandBuffer endRenderPass();

        /** Finish recording and return a submittable command buffer. */
        CommandBuffer end();
    }

    /** Resource access type for automatic barriers. */
    enum Access {
        /** No access. */
        NONE,
        /** Sampled shader read. */
        SHADER_READ,
        /** Storage image read/write for compute. */
        SHADER_WRITE,
        /** Color attachment write. */
        COLOR_ATTACHMENT,
        /** Depth attachment read/write. */
        DEPTH_STENCIL,
        /** Transfer read. */
        TRANSFER_READ,
        /** Transfer write. */
        TRANSFER_WRITE,
        /** Presentation engine read. */
        PRESENT
    }

    /**
     * GPU semaphore.
     * @param nativeHandle native {@code VkSemaphore}; zero delegates management to the host
     * @param timeline whether this is a Vulkan 1.2+ timeline semaphore; enables {@link #value()}
     * @param value timeline value
     * @param label debug label
     */
    record GpuSemaphore(long nativeHandle, boolean timeline, long value, String label) {
        public GpuSemaphore {
            Objects.requireNonNull(label, "label");
        }

        public static GpuSemaphore binary(long nativeHandle, String label) {
            return new GpuSemaphore(nativeHandle, false, 0L, label);
        }

        public static GpuSemaphore timeline(long nativeHandle, long value, String label) {
            return new GpuSemaphore(nativeHandle, true, value, label);
        }

        public boolean isNull() {
            return nativeHandle == 0L;
        }
    }
}
