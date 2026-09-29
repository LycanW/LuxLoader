package dev.luxloader.core.vulkan;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.gpu.ComputePipelineDesc;
import dev.luxloader.api.gpu.GpuCommands;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.gpu.GpuQueue;
import dev.luxloader.api.gpu.GraphicsPipelineDesc;
import dev.luxloader.api.gpu.ImageHandle;
import dev.luxloader.api.gpu.ImageViewDesc;
import dev.luxloader.api.gpu.RenderPassDesc;
import dev.luxloader.api.gpu.NativeVulkanContext;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import org.lwjgl.vulkan.VkImageBlit;
import static org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_TIMELINE;
import static org.lwjgl.vulkan.VK12.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Command recording with loader-managed resource state and barriers. Transition/image/attachment
 * operations declare layouts and access; the scheduler handles cross-command-buffer synchronization.
 * barrierRaw remains available for explicit low-level control.
 */
public final class VulkanCommands implements GpuCommands, AutoCloseable {

    private final VulkanDevice device;
    private final VulkanUploadArena uploadArena;
    private final List<VulkanUploadArena.Lease> hostUploadLeases = new ArrayList<>();
    private final long pool;
    private final Map<String, Long> descriptorSetLayouts = new LinkedHashMap<>();
    private static final int DESCRIPTOR_SETS_PER_PAGE = 64;
    private final Map<String, DescriptorPoolPages> descriptorPools = new LinkedHashMap<>();

    private static final class DescriptorPoolPage {
        final long handle;
        int allocatedSets;

        DescriptorPoolPage(long handle) { this.handle = handle; }
    }

    private static final class DescriptorPoolPages {
        final List<DescriptorPoolPage> pages = new ArrayList<>();
        int current;
    }
    private final Map<String, Long> computePipelines = new LinkedHashMap<>();
    private final Map<String, Long> graphicsPipelines = new LinkedHashMap<>();
    private final Map<SpirvKey, Long> shaderModules = new LinkedHashMap<>();

    /**
     * Shader-module cache key uses complete SPIR-V byte equality, not a 32-bit hash alone. Hash collisions
     * must not select unrelated modules. Array record components require explicit content equals/hashCode
     * and a defensive copy to keep keys stable.
     */
    record SpirvKey(byte[] bytes) {
        SpirvKey {
            bytes = bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SpirvKey key && java.util.Arrays.equals(bytes, key.bytes);
        }

        @Override
        public int hashCode() {
            // Hashing selects a bucket; equals compares complete bytes for correctness.
            return java.util.Arrays.hashCode(bytes);
        }
    }
    private final Map<String, Long> pipelineLayouts = new LinkedHashMap<>();
    private final Map<Long, Long> bufferMemory = new HashMap<>();
    private final List<Long> createdObjects = new ArrayList<>();
    private final List<Long> pendingDescriptorSets = new ArrayList<>();
    private final Map<String, Long> samplers = new LinkedHashMap<>();

    /**
     * Recorded but unsubmitted buffers retained for centralized queue grouping and submission
     * independently of graph node ownership.
     */
    private final List<CommandBufferImpl> recorded = new ArrayList<>();

    private static final class PendingImageReadback {
        final long buffer;
        final long memory;
        final long size;
        final dev.luxloader.api.gpu.ImageDesc description;
        final Consumer<GpuCommands.ImageReadback> completed;
        final Consumer<Throwable> failed;

        PendingImageReadback(long buffer, long memory, long size,
                             dev.luxloader.api.gpu.ImageDesc description,
                             Consumer<GpuCommands.ImageReadback> completed,
                             Consumer<Throwable> failed) {
            this.buffer = buffer;
            this.memory = memory;
            this.size = size;
            this.description = description;
            this.completed = completed;
            this.failed = failed;
        }
    }

    private static final class PendingHostFrame {
        final long fence;
        final List<PendingImageReadback> readbacks;
        final List<VulkanUploadArena.Lease> uploadLeases;
        final boolean captureFrame;

        PendingHostFrame(long fence, List<PendingImageReadback> readbacks,
                         List<VulkanUploadArena.Lease> uploadLeases, boolean captureFrame) {
            this.fence = fence;
            this.readbacks = readbacks;
            this.uploadLeases = uploadLeases;
            this.captureFrame = captureFrame;
        }
    }

    private final List<PendingImageReadback> frameImageReadbacks = new ArrayList<>();
    private final ArrayList<PendingHostFrame> pendingHostFrames = new ArrayList<>();

    /** Minecraft owns these command buffers and submits them with its current frame. */
    private java.util.function.Supplier<VkCommandBuffer> hostAllocate;
    private java.util.function.Consumer<VkCommandBuffer> hostExecute;

    public <T> T withHostEncoder(java.util.function.Supplier<VkCommandBuffer> allocate,
                                 java.util.function.Consumer<VkCommandBuffer> execute,
                                 java.util.function.Supplier<T> work) {
        java.util.Objects.requireNonNull(allocate, "allocate");
        java.util.Objects.requireNonNull(execute, "execute");
        java.util.Objects.requireNonNull(work, "work");
        if (hostAllocate != null || !recorded.isEmpty()) {
            throw new IllegalStateException("A command batch is already being recorded");
        }
        hostAllocate = allocate;
        hostExecute = execute;
        try {
            return work.get();
        } finally {
            // An exception must never leave an incomplete plugin buffer in the
            // next frame's batch. Minecraft owns the transient command pool.
            recorded.clear();
            hostAllocate = null;
            hostExecute = null;
        }
    }

    /** Drop unsubmitted initialization work before its resources are destroyed. */
    public void discardPendingRecordings() {
        if (hostAllocate != null) {
            throw new IllegalStateException("Cannot discard a Minecraft-owned frame batch here");
        }
        var batch = new ArrayList<>(recorded);
        recorded.clear();
        freeBatch(batch);
    }

    public boolean isHostFrameRecording() {
        return hostAllocate != null;
    }

    @Override public boolean supportsPreparationSubmission() { return hostAllocate == null; }

    @Override public void completePreparation(GpuQueue queue) {
        if (hostAllocate != null) throw new IllegalStateException("Preparation cannot submit inside a host frame");
        // Publication correctness cannot depend on deduplicated diagnostic messages.
        if (recorded.stream().anyMatch(buffer -> !buffer.hasEnded())) {
            discardPendingRecordings();
            throw new IllegalStateException(tr("Preparation contains an unfinished recording"));
        }
        if (recorded.isEmpty()) return;
        java.util.Objects.requireNonNull(queue, "queue");
        var batch = new ArrayList<>(recorded);
        long fence = acquireFreeFence();
        if (fence == 0L) {
            discardPendingRecordings();
            throw new IllegalStateException(tr("Preparation completion fence is unavailable"));
        }
        recorded.clear();
        int reset = vkResetFences(device.vkDevice(), fence);
        if (reset != VK_SUCCESS) {
            freeBatch(batch);
            releaseFreeFence(fence);
            throw new IllegalStateException(tr("Preparation fence reset failed: ") + VulkanDevice.resultName(reset));
        }
        PreparationSubmission.complete(
                () -> submitWithFence(queue, new ArrayList<>(batch), List.of(), List.of(), fence),
                () -> vkWaitForFences(device.vkDevice(), fence, true, Long.MAX_VALUE),
                () -> { freeBatch(batch); releaseFreeFence(fence); },
                () -> unconfirmedPreparationBatches.add(new PendingSubmission(batch, fence)));
    }

    /** Command buffer reclamation failures for diagnostics. */
    private final List<String> recyclingFailures = new ArrayList<>();

    private long timelineCounter;

    /**
     * Provider-injected image-view resolver. Commands know native images; the provider retains
     * resource/view ownership and lifecycle responsibility.
     */
    private java.util.function.LongFunction<Long> viewResolver;
    private boolean dynamicRenderingExtensionNoted;

    /** Bound pipeline description for descriptor names/types. */

    /** Current pipeline layout for push constants. */

    VulkanCommands(VulkanDevice device) {
        this.device = device;
        this.uploadArena = new VulkanUploadArena(device);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(device.graphicsQueue().familyIndex());
            LongBuffer pPool = stack.mallocLong(1);
            int err = vkCreateCommandPool(device.vkDevice(), info, null, pPool);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(
                        tr("Failed to create command pool: ") + VulkanDevice.resultName(err));
            }
            this.pool = pPool.get(0);
        }
    }

    @Override
    public CommandBuffer begin(String label) {
        if (hostAllocate != null) {
            CommandBufferImpl buffer = new CommandBufferImpl(hostAllocate.get(),
                    label == null ? "cmd" : label, 0L);
            recorded.add(buffer);
            return buffer;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo alloc = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            int err = vkAllocateCommandBuffers(device.vkDevice(), alloc, pCmd);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(
                        tr("Failed to allocate command buffer: ") + VulkanDevice.resultName(err));
            }
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device.vkDevice());
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            int beginErr = vkBeginCommandBuffer(cmd, begin);
            if (beginErr != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(
                        tr("vkBeginCommandBuffer failed: ") + VulkanDevice.resultName(beginErr));
            }
            CommandBufferImpl buffer = new CommandBufferImpl(cmd, label == null ? "cmd" : label, pool);
            recorded.add(buffer);
            return buffer;
        }
    }

    private final java.util.ArrayDeque<Long> spareSemaphores = new java.util.ArrayDeque<>();

    /**
     * Pools binary semaphores to avoid per-frame native-handle growth. Reuse only after frame-end drain
     * confirms all waiting batches consumed their signals.
     */
    public GpuSemaphore acquireSemaphore(String label) {
        Long spare = spareSemaphores.poll();
        if (spare != null) {
            return GpuSemaphore.binary(spare, label);
        }
        return createSemaphore(label);
    }

    /**
     * Returns only a semaphore whose signal has been consumed. Reusing an already-signaled semaphore would
     * invalidate inter-batch ordering.
     */
    public void releaseSemaphore(GpuSemaphore semaphore) {
        if (semaphore != null && semaphore.nativeHandle() != 0L) {
            spareSemaphores.add(semaphore.nativeHandle());
        }
    }

    @Override
    public GpuSemaphore createSemaphore(String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer pSem = stack.mallocLong(1);
            int err = vkCreateSemaphore(device.vkDevice(), info, null, pSem);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(
                        tr("Failed to create semaphore: ") + VulkanDevice.resultName(err));
            }
            createdObjects.add(pSem.get(0));
            return GpuSemaphore.binary(pSem.get(0), label == null ? "sem" : label);
        }
    }

    /** Creates a Vulkan 1.2+ timeline semaphore for frame/async synchronization. */
    public GpuSemaphore createTimelineSemaphore(String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
                    .sType$Default()
                    .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(0L);
            VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(typeInfo.address());
            LongBuffer pSem = stack.mallocLong(1);
            int err = vkCreateSemaphore(device.vkDevice(), info, null, pSem);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(
                        tr("Failed to create timeline semaphore: ") + VulkanDevice.resultName(err));
            }
            createdObjects.add(pSem.get(0));
            return GpuSemaphore.timeline(pSem.get(0), ++timelineCounter, label == null ? "timeline" : label);
        }
    }

    @Override
    public void submit(GpuQueue queue, List<CommandBuffer> buffers, List<GpuSemaphore> wait,
                       List<GpuSemaphore> signal) {
        int err = submitWithFence(queue, buffers, wait, signal, VK_NULL_HANDLE);
        if (err != VK_SUCCESS) {
            throw new dev.luxloader.core.util.LuxException(
                    tr("vkQueueSubmit failed: ") + VulkanDevice.resultName(err));
        }
    }

    /**
     * Submit a batch, optionally attaching a fence.
     *
     * <p>A non-null fence must be unsignaled and must not already be referenced by an
     * outstanding submission (VUID-vkQueueSubmit-fence-00063 / -00064). Keeping this
     * private lets the public submit() keep its fire-and-forget semantics while flush()
     * can wait for exactly the batch it is about to release.
     *
     * @return VK_SUCCESS or the raw vkQueueSubmit error code, so the caller can decide
     *         whether the batch may be freed.
     */
    private int submitWithFence(GpuQueue queue, List<CommandBuffer> buffers, List<GpuSemaphore> wait,
                                List<GpuSemaphore> signal, long fence) {
        if (buffers == null || buffers.isEmpty()) {
            return VK_SUCCESS;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer cmdBuffers = stack.mallocPointer(buffers.size());
            for (CommandBuffer cb : buffers) {
                if (!(cb instanceof CommandBufferImpl impl)) {
                    throw new IllegalArgumentException(tr("Command buffer was not created by this implementation: ") + cb);
                }
                cmdBuffers.put(impl.handle().address());
            }
            cmdBuffers.flip();

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(cmdBuffers);

            if (wait != null && !wait.isEmpty()) {
                LongBuffer waits = stack.mallocLong(wait.size());
                IntBuffer stages = stack.mallocInt(wait.size());
                for (GpuSemaphore s : wait) {
                    waits.put(s.nativeHandle());
                }
                for (int i = 0; i < wait.size(); i++) {
                    stages.put(i, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
                }
                waits.flip();
                stages.flip();
                submit.waitSemaphoreCount(wait.size()).pWaitSemaphores(waits).pWaitDstStageMask(stages);
            }
            if (signal != null && !signal.isEmpty()) {
                LongBuffer signals = stack.mallocLong(signal.size());
                for (GpuSemaphore s : signal) {
                    signals.put(s.nativeHandle());
                }
                signals.flip();
                submit.pSignalSemaphores(signals);
            }

            org.lwjgl.vulkan.VkQueue vkQueue = new org.lwjgl.vulkan.VkQueue(
                    queue.nativeQueue(), device.vkDevice());
            traceSubmission("submitWithFence", buffers);
            return vkQueueSubmit(vkQueue, submit, fence);
        }
    }

    @Override
    public void waitIdle(GpuQueue queue) {
        org.lwjgl.vulkan.VkQueue vkQueue = new org.lwjgl.vulkan.VkQueue(
                queue.nativeQueue(), device.vkDevice());
        vkQueueWaitIdle(vkQueue);
    }

    @Override
    public void waitIdleAll() {
        device.waitIdleAll();
    }

    /**
     * Submits and waits before reclaiming recorded buffers, combining flushAsync and drainSubmissions.
     * Multi-batch callers can submit all batches first and drain once.
     */
    @Override
    public int flush(GpuQueue queue, List<GpuSemaphore> wait, List<GpuSemaphore> signal) {
        int considered = flushAsync(queue, wait, signal);
        drainSubmissions();
        return considered;
    }

    /** Submitted batches awaiting completion. */
    private record PendingSubmission(List<CommandBufferImpl> batch, long fence) {
    }

    private final List<PendingSubmission> pendingSubmissions = new ArrayList<>();
    // An exception before native submission can leave an unsignaled fence forever. Never drain
    // these as successful submissions. The device's eventual destruction owns their native storage.
    private final List<PendingSubmission> unconfirmedPreparationBatches = new ArrayList<>();

    /**
     * Submits without waiting or reclaiming so graphics/compute batches may overlap. Drain all submissions
     * at frame end before releasing pending resources.
     * @return affected buffer count, including discarded unfinished recordings
     */
    public int flushAsync(GpuQueue queue, List<GpuSemaphore> wait, List<GpuSemaphore> signal) {
        if (recorded.isEmpty()) {
            return 0;
        }
        List<CommandBufferImpl> batch = new ArrayList<>(recorded);
        recorded.clear();
        int considered = batch.size();

        // Remove recordings that failed before end(); see dropUnterminated.
        dropUnterminated(batch);
        if (batch.isEmpty()) {
            // If all recordings are unfinished, skip submission and fence allocation.
            return considered;
        }

        if (hostExecute != null) {
            if ((wait != null && !wait.isEmpty()) || (signal != null && !signal.isEmpty())) {
                throw new IllegalStateException("Host frame batches cannot use independent queue semaphores");
            }
            for (CommandBufferImpl buffer : batch) {
                hostExecute.accept(buffer.handle());
            }
            return considered;
        }

        boolean mayFree = false;
        long fence = acquireFreeFence();
        try {
            if (fence != 0L) {
                // The spec allows resetting an already-unsignaled fence, and resetting
                // makes VUID-vkQueueSubmit-fence-00063 hold.
                vkResetFences(device.vkDevice(), fence);
            }
            int err = submitWithFence(queue, new ArrayList<>(batch), wait, signal, fence);
            if (err != VK_SUCCESS) {
                // Only the two out-of-memory codes guarantee that referenced resources are
                // unaffected; anything else (DEVICE_LOST included) must be treated as
                // possibly pending, so the batch is deliberately not freed.
                if (err == VK_ERROR_OUT_OF_HOST_MEMORY || err == VK_ERROR_OUT_OF_DEVICE_MEMORY) {
                    mayFree = true;
                } else {
                    recyclingFailures.add("vkQueueSubmit failed (" + VulkanDevice.resultName(err)
                            + "); " + batch.size() + " command buffers left unreleased");
                }
                throw new dev.luxloader.core.util.LuxException(
                        tr("vkQueueSubmit failed: ") + VulkanDevice.resultName(err));
            }
            if (fence != 0L) {
                // Track successful submissions for drainSubmissions to wait and reclaim.
                pendingSubmissions.add(new PendingSubmission(batch, fence));
                return considered;
            }
            // Without a fence, wait for the queue immediately before another submission can intervene.
            wholeQueueWaitCount++;
            vkQueueWaitIdle(new org.lwjgl.vulkan.VkQueue(
                    queue.nativeQueue(), device.vkDevice()));
            mayFree = true;
        } catch (RuntimeException e) {
            recyclingFailures.add(tr("Submit failed: ") + e);
        }
        if (!mayFree) {
            // Retain resources if completion cannot be proven; freeing pending resources is undefined behavior.
            recyclingFailures.add(tr("Batch completion unconfirmed; retaining command buffers to avoid undefined behavior"));
            return considered;
        }
        freeBatch(batch);
        releaseFreeFence(fence);
        return considered;
    }

    /**
     * Waits and reclaims all in-flight batches.
     * @return batch count
     */
    public int drainSubmissions() {
        if (pendingSubmissions.isEmpty()) {
            return 0;
        }
        List<PendingSubmission> pending = new ArrayList<>(pendingSubmissions);
        pendingSubmissions.clear();
        for (PendingSubmission submission : pending) {
            if (!awaitFreeFence(submission.fence())) {
                // Do not recycle a fence that the GPU may still reference.
                recyclingFailures.add(tr("Batch completion unconfirmed; retaining command buffers to avoid undefined behavior"));
                continue;
            }
            freeBatch(submission.batch());
            releaseFreeFence(submission.fence());
        }
        return pending.size();
    }

    private void freeBatch(List<CommandBufferImpl> batch) {
        for (CommandBufferImpl buffer : batch) {
            try {
                buffer.free();
            } catch (RuntimeException e) {
                // Report cleanup failures without hiding submission results; leaked buffers can degrade long-running performance.
                recyclingFailures.add(buffer.label + ": " + e);
            }
        }
    }

    /** Deduplicates reports about unfinished command buffers. */
    private boolean unterminatedNoted;

    /**
     * Removes and frees buffers whose passes failed before end(). Queue submission requires executable
     * buffers (VUID 00070); unfinished recordings were never pending and can be freed immediately.
     * Deduplicate the diagnostic to retain evidence without per-frame log growth.
     * @return discarded count
     */
    private int dropUnterminated(List<CommandBufferImpl> batch) {
        List<CommandBufferImpl> dropped = null;
        for (java.util.Iterator<CommandBufferImpl> it = batch.iterator(); it.hasNext(); ) {
            CommandBufferImpl buffer = it.next();
            if (buffer.hasEnded()) {
                continue;
            }
            it.remove();
            if (dropped == null) {
                dropped = new ArrayList<>(2);
            }
            dropped.add(buffer);
        }
        if (dropped == null) {
            return 0;
        }
        if (!unterminatedNoted) {
            unterminatedNoted = true;
            recyclingFailures.add(tr("A pass failed during recording: ")
                    + dropped.size() + tr(" command buffers without end() were discarded and released (for example ")
                    + dropped.get(0).label + tr("). Submitting these recording-state buffers ")
                    + tr("violates VUID-vkQueueSubmit-pCommandBuffers-00070. This notice appears only once."));
        }
        for (CommandBufferImpl buffer : dropped) {
            try {
                buffer.free();
            } catch (RuntimeException e) {
                recyclingFailures.add(buffer.label + tr(" (not ended): ") + e);
            }
        }
        return dropped.size();
    }

    /**
     * Fence pool proves each submission completed before buffer reclamation (VUID 00047), avoiding
     * whole-queue waits that include unrelated host work. Each in-flight submission needs its own fence.
     */
    private final java.util.ArrayDeque<Long> spareFences = new java.util.ArrayDeque<>();

    // GPU frame timestamps use two alternating slots, written at the same final-work point each frame. After completion, subtract consecutive timestamps. Never read a nonexistent previous timestamp on the first frame; waiting on an unwritten query can hang indefinitely.

    private long timestampQueryPool;
    private int timestampedFrameCount;
    /** Whether timestamp unavailability was reported. */
    private boolean timestampPoolBailed;

    /** Lazily creates the query pool; false means unavailable, without fabricated timing. */
    private boolean ensureTimestampPool() {
        if (timestampQueryPool != 0L) {
            return true;
        }
        if (!timestampPoolBailed) {
            // Record why timing is unavailable instead of silently abandoning measurement.
            if (device.capabilities() == null) {
                timestampPoolBailed = true;
                device.diagnostics().warn(tr("GPU frame timing unavailable: cannot read device capabilities")
                        + tr(" (timestampPeriod is unknown)"));
                return false;
            }
            if (!device.capabilities().supportsTimestamps()) {
                timestampPoolBailed = true;
                device.diagnostics().warn(tr("GPU frame timing unavailable: device timestampPeriod = ")
                        + device.capabilities().timestampPeriod()
                        + tr(" (must be positive to convert ticks to nanoseconds)"));
                return false;
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkQueryPoolCreateInfo info =
                    org.lwjgl.vulkan.VkQueryPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .queryType(VK_QUERY_TYPE_TIMESTAMP)
                            .queryCount(2);
            LongBuffer pPool = stack.mallocLong(1);
            int err = vkCreateQueryPool(device.vkDevice(), info, null, pPool);
            if (err != VK_SUCCESS) {
                recyclingFailures.add("vkCreateQueryPool failed (" + VulkanDevice.resultName(err)
                        + tr("); GPU frame time remains zero"));
                return false;
            }
            timestampQueryPool = pPool.get(0);
            return true;
        }
    }

    /** Writes a timestamp and increments timestampedFrameCount for two-frame availability checks. */
    void stampFrameTimestamp(CommandBufferImpl cmd) {
        if (!ensureTimestampPool()) {
            return;
        }
        int slot = timestampedFrameCount % 2;
        // Reset and write in the same command buffer; unwritten queries can be reset without a separate initialization submission.
        vkCmdResetQueryPool(cmd.handle(), timestampQueryPool, slot, 1);
        vkCmdWriteTimestamp(cmd.handle(), VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                timestampQueryPool, slot);
        timestampedFrameCount++;
    }

    /**
     * Latest GPU interval in nanoseconds, or zero before two frames/when unsupported or unavailable. Call
     * only after command completion, such as after drainSubmissions.
     */
    public long lastGpuFrameTimeNs() {
        if (timestampQueryPool == 0L || timestampedFrameCount < 2) {
            return 0L;
        }
        int newer = (timestampedFrameCount - 1) % 2;
        int older = (timestampedFrameCount - 2) % 2;
        long[] pair = new long[2];
        if (!readTimestampQuery(newer, pair, 0) || !readTimestampQuery(older, pair, 1)) {
            return 0L;
        }
        long delta = pair[0] - pair[1];
        if (delta <= 0L) {
            return 0L;
        }
        float periodNs = device.capabilities().timestampPeriod();
        if (periodNs <= 0f) {
            return 0L;
        }
        return (long) (delta * periodNs);
    }

    private boolean readTimestampQuery(int slot, long[] out, int index) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pData = stack.mallocLong(1);
            // Never use QUERY_RESULT_WAIT_BIT for potentially unwritten queries. Without WAIT, unavailable results return NOT_READY and can be skipped.
            int err = vkGetQueryPoolResults(device.vkDevice(), timestampQueryPool, slot, 1,
                    pData, Long.BYTES, VK_QUERY_RESULT_64_BIT);
            if (err != VK_SUCCESS) {
                return false;
            }
            out[index] = pData.get(0);
            return true;
        }
    }

    /**
     * Acquires a per-submission fence, creating if necessary; zero requests a whole-queue fallback wait.
     * Multiple in-flight batches must not share one completion signal. Recycle only after completion and
     * reclamation.
     */
    private long acquireFreeFence() {
        Long spare = spareFences.poll();
        if (spare != null) {
            return spare;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pFence = stack.mallocLong(1);
            int err = vkCreateFence(device.vkDevice(),
                    VkFenceCreateInfo.calloc(stack).sType$Default(), null, pFence);
            if (err != VK_SUCCESS) {
                recyclingFailures.add("vkCreateFence failed (" + VulkanDevice.resultName(err)
                        + "); falling back to a whole-queue wait");
                return 0L;
            }
            return pFence.get(0);
        }
    }

    /**
     * Returns a completed/signaled fence only after awaitFreeFence succeeds, preventing premature
     * reclamation from reused completion state.
     */
    private void releaseFreeFence(long fence) {
        if (fence != 0L) {
            spareFences.add(fence);
        }
    }

    /**
     * Wait until the given fence signals.
     *
     * @return true when the batch it guards is provably no longer pending
     */
    private boolean awaitFreeFence(long fence) {
        int err = vkWaitForFences(device.vkDevice(), fence, true, Long.MAX_VALUE);
        if (err == VK_SUCCESS) {
            return true;
        }
        if (err == VK_ERROR_DEVICE_LOST) {
            // Spec: for the purpose of deciding whether a command buffer is pending,
            // VK_ERROR_DEVICE_LOST is equivalent to VK_SUCCESS.
            recyclingFailures.add("recycle fence: device lost (treated as completed)");
            return true;
        }
        recyclingFailures.add("vkWaitForFences returned " + VulkanDevice.resultName(err));
        return false;
    }

    /** Counts whole-queue fallback waits so diagnostics can verify that the fence path avoids them. */
    private long wholeQueueWaitCount;

    /** Cumulative whole-queue waits. */
    public long wholeQueueWaitCount() {
        return wholeQueueWaitCount;
    }

    public java.util.List<String> recyclingFailures() {
        return new ArrayList<>(recyclingFailures);
    }

    /** Pending recording count. */
    public int pendingRecordingCount() {
        return recorded.size();
    }

    private long passTimingPool;
    private long timingFrame;
    private boolean passTimingUnavailable;
    private final java.util.Map<String, java.util.List<Integer>> passTimingSlots = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Integer> activeTimingSlots = new java.util.HashMap<>();
    private final java.util.Set<String> incompleteTimings = new java.util.HashSet<>();
    private int timingPairCount;
    private final java.util.Map<String, Double> passTimingAverages = new java.util.HashMap<>();
    private final java.util.Map<String, Long> passCpuStarts = new java.util.HashMap<>();

    @Override public boolean timestampSampleDue() {
        return timingFrame % 30 == 0 && !passTimingUnavailable && device.capabilities().supportsTimestamps();
    }

    @Override public void beginTimestamp(String label) {
        // Profile one in 30 frames; avoid adding command buffers to every frame.
        if (timingFrame % 30 != 0) return;
        passCpuStarts.put(label, System.nanoTime());
        if (passTimingUnavailable || !device.capabilities().supportsTimestamps()) return;
        if (timingPairCount >= 64 || activeTimingSlots.containsKey(label)) {
            incompleteTimings.add(label);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (passTimingPool == 0) {
                var info = org.lwjgl.vulkan.VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(128);
                var out = stack.mallocLong(1);
                if (vkCreateQueryPool(device.vkDevice(), info, null, out) != VK_SUCCESS) {
                    passTimingUnavailable = true;
                    return;
                }
                passTimingPool = out.get(0);
            }
            int slot = timingPairCount++ * 2;
            passTimingSlots.computeIfAbsent(label, ignored -> new java.util.ArrayList<>()).add(slot);
            activeTimingSlots.put(label, slot);
            var command = (CommandBufferImpl) begin("timing-start/" + label);
            vkCmdResetQueryPool(command.handle(), passTimingPool, slot, 2);
            vkCmdWriteTimestamp(command.handle(), VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, passTimingPool, slot);
            command.end();
        }
    }

    @Override public void endTimestamp(String label) {
        Long cpu = passCpuStarts.remove(label);
        if (cpu != null && device.diagnostics() != null) device.diagnostics().metric(
                "cpu.encode." + label, (System.nanoTime() - cpu) / 1_000_000.0, "ms");
        Integer slot = activeTimingSlots.remove(label);
        if (slot == null) return;
        var command = (CommandBufferImpl) begin("timing-end/" + label);
        vkCmdWriteTimestamp(command.handle(), VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, passTimingPool, slot + 1);
        command.end();
    }

    /** Called only at the existing completed-frame recycling boundary; never waits for profiling. */
    public void collectPassTimings() {
        if (passTimingPool == 0 || passTimingSlots.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var times = stack.mallocLong(2);
            for (var entry : passTimingSlots.entrySet()) {
                if (incompleteTimings.contains(entry.getKey()) || activeTimingSlots.containsKey(entry.getKey())) continue;
                double millis = 0;
                boolean complete = true;
                // A batched pass can use the same label several times. Each pair
                // is written exactly once, then durations are summed by label.
                for (int slot : entry.getValue()) {
                    int result = vkGetQueryPoolResults(device.vkDevice(), passTimingPool, slot, 2,
                            times, Long.BYTES, VK_QUERY_RESULT_64_BIT);
                    if (result != VK_SUCCESS || times.get(1) < times.get(0)) { complete = false; break; }
                    millis += (times.get(1) - times.get(0)) * device.capabilities().timestampPeriod() / 1_000_000.0;
                }
                if (!complete) continue;
                double average = passTimingAverages.merge(entry.getKey(), millis, (old, now) -> old * .9 + now * .1);
                if (device.diagnostics() != null) {
                    device.diagnostics().metric("gpu.pass." + entry.getKey(), millis, "ms");
                    device.diagnostics().metric("gpu.pass.avg." + entry.getKey(), average, "ms");
                }
            }
        }
        passTimingSlots.clear();
        activeTimingSlots.clear(); incompleteTimings.clear(); timingPairCount = 0;
        passCpuStarts.clear();
    }

    // Device-level helpers.

    /** Synchronous staging upload for initialization, never per-frame work. */
    void uploadToBuffer(long bufferHandle, long offset, ByteBuffer data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long size = data.remaining();
            org.lwjgl.vulkan.VkBufferCreateInfo info = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            int err = vkCreateBuffer(device.vkDevice(), info, null, pBuf);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(tr("Failed to create staging buffer: ") + VulkanDevice.resultName(err));
            }
            long staging = pBuf.get(0);
            try {
                org.lwjgl.vulkan.VkMemoryRequirements req = org.lwjgl.vulkan.VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(device.vkDevice(), staging, req);
                int typeIndex = findMemoryType(req.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                org.lwjgl.vulkan.VkMemoryAllocateInfo alloc = org.lwjgl.vulkan.VkMemoryAllocateInfo.calloc(stack)
                        .sType$Default()
                        .allocationSize(req.size())
                        .memoryTypeIndex(typeIndex);
                LongBuffer pMem = stack.mallocLong(1);
                err = vkAllocateMemory(device.vkDevice(), alloc, null, pMem);
                if (err != VK_SUCCESS) {
                    throw new dev.luxloader.core.util.LuxException(tr("Failed to allocate staging memory: ")
                            + VulkanDevice.resultName(err));
                }
                long memory = pMem.get(0);
                try {
                    vkBindBufferMemory(device.vkDevice(), staging, memory, 0);
                    org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
                    err = vkMapMemory(device.vkDevice(), memory, 0, size, 0, pData);
                    if (err != VK_SUCCESS) {
                        throw new dev.luxloader.core.util.LuxException(tr("Failed to map staging buffer: ")
                                + VulkanDevice.resultName(err));
                    }
                    MemoryUtil.memCopy(MemoryUtil.memAddress(data), pData.get(0), size);
                    vkUnmapMemory(device.vkDevice(), memory);

                    CommandBufferImpl cmd = (CommandBufferImpl) begin("upload");
                    VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                    region.get(0).srcOffset(0).dstOffset(offset).size(size);
                    vkCmdCopyBuffer(cmd.handle(), staging, bufferHandle, region);
                    cmd.end();
                    submitAndWait(cmd, device.graphicsQueue());
                } finally {
                    vkFreeMemory(device.vkDevice(), memory, null);
                }
            } finally {
                vkDestroyBuffer(device.vkDevice(), staging, null);
            }
        }
    }

    /** Blocking diagnostic/test readback; never call every frame. */
    /**
     * Blocking one-pixel readback for diagnostics, never recurring frame work. Use the actual old layout
     * to preserve contents; UNDEFINED may discard the very evidence being sampled.
     * @return raw pixel bytes for the format
     */
    public int[] readPixelBlocking(long image, int vkFormat, int x, int y) {
        int bpp = bytesPerPixel(vkFormat);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCreateInfo info = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(bpp)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            int err = vkCreateBuffer(device.vkDevice(), info, null, pBuf);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(tr("Failed to create pixel readback buffer: ")
                        + VulkanDevice.resultName(err));
            }
            long staging = pBuf.get(0);
            long memory = 0L;
            try {
                org.lwjgl.vulkan.VkMemoryRequirements req = org.lwjgl.vulkan.VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(device.vkDevice(), staging, req);
                int typeIndex = findMemoryType(req.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                org.lwjgl.vulkan.VkMemoryAllocateInfo alloc = org.lwjgl.vulkan.VkMemoryAllocateInfo.calloc(stack)
                        .sType$Default()
                        .allocationSize(req.size())
                        .memoryTypeIndex(typeIndex);
                LongBuffer pMem = stack.mallocLong(1);
                err = vkAllocateMemory(device.vkDevice(), alloc, null, pMem);
                if (err != VK_SUCCESS) {
                    throw new dev.luxloader.core.util.LuxException(tr("Failed to allocate pixel readback memory: ")
                            + VulkanDevice.resultName(err));
                }
                memory = pMem.get(0);
                vkBindBufferMemory(device.vkDevice(), staging, memory, 0);

                CommandBufferImpl cmd = (CommandBufferImpl) begin("pixel-readback");
                cmd.transitionKeepingContents(dev.luxloader.api.gpu.ImageHandle.vkImage(image, "pixel-readback"),
                        dev.luxloader.api.gpu.ImageViewDesc.full(),
                        dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_READ);

                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                region.get(0).imageSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                        .baseArrayLayer(0).layerCount(1);
                region.get(0).imageOffset().set(x, y, 0);
                region.get(0).imageExtent().set(1, 1, 1);
                vkCmdCopyImageToBuffer(cmd.handle(), image,
                        currentLayout(image), staging, region);
                cmd.end();
                submitAndWait(cmd, device.graphicsQueue());

                org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
                vkMapMemory(device.vkDevice(), memory, 0, bpp, 0, pData);
                ByteBuffer mapped = org.lwjgl.system.MemoryUtil.memByteBuffer(pData.get(0), bpp);
                int[] out = new int[bpp];
                for (int i = 0; i < bpp; i++) {
                    out[i] = mapped.get(i) & 0xFF;
                }
                vkUnmapMemory(device.vkDevice(), memory);
                return out;
            } finally {
                vkDestroyBuffer(device.vkDevice(), staging, null);
                if (memory != 0L) {
                    vkFreeMemory(device.vkDevice(), memory, null);
                }
            }
        }
    }

    @Override
    public boolean readImageAsync(ImageHandle image, dev.luxloader.api.gpu.ImageDesc description,
                                  Consumer<GpuCommands.ImageReadback> completed,
                                  Consumer<Throwable> failed) {
        if (image == null || image.isNull() || image.kind() != ImageHandle.Kind.VK_IMAGE
                || description == null || completed == null || failed == null
                || description.width() <= 0 || description.height() <= 0
                || description.samples() != 1 || description.format() == null
                || !description.format().isDefined() || description.format().isDepth()
                || description.format().bytesPerPixel() <= 0
                || description.has(dev.luxloader.api.gpu.ImageDesc.Usage.EXTERNAL)
                || !description.has(dev.luxloader.api.gpu.ImageDesc.Usage.TRANSFER_SRC)
                || externalImages.contains(image.bits())) {
            return false;
        }
        Integer previousLayout = imageLayouts.get(image.bits());
        if (previousLayout == null || previousLayout == VK_IMAGE_LAYOUT_UNDEFINED) {
            return false;
        }
        GpuCommands.Access restoreAccess = accessForTrackedLayout(previousLayout);
        if (restoreAccess == null) {
            return false;
        }

        int width = description.width();
        int height = description.height();
        int bytesPerPixel = description.format().bytesPerPixel();
        long size = (long) width * height * bytesPerPixel;
        if (size <= 0 || size > Integer.MAX_VALUE) {
            failed.accept(new IllegalArgumentException("Image readback exceeds supported byte range"));
            return true;
        }

        long staging = 0L;
        long memory = 0L;
        PendingImageReadback readback = null;
        CommandBufferImpl command = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack)
                    .sType$Default().size(size).usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            int error = vkCreateBuffer(device.vkDevice(), info, null, pBuffer);
            if (error != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException("Failed to create asynchronous image staging buffer: "
                        + VulkanDevice.resultName(error));
            }
            staging = pBuffer.get(0);

            var requirements = org.lwjgl.vulkan.VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device.vkDevice(), staging, requirements);
            int memoryType = findMemoryType(requirements.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            var allocation = org.lwjgl.vulkan.VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default().allocationSize(requirements.size()).memoryTypeIndex(memoryType);
            LongBuffer pMemory = stack.mallocLong(1);
            error = vkAllocateMemory(device.vkDevice(), allocation, null, pMemory);
            if (error != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException("Failed to allocate asynchronous image staging memory: "
                        + VulkanDevice.resultName(error));
            }
            memory = pMemory.get(0);
            error = vkBindBufferMemory(device.vkDevice(), staging, memory, 0L);
            if (error != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException("Failed to bind asynchronous image staging memory: "
                        + VulkanDevice.resultName(error));
            }

            readback = new PendingImageReadback(staging, memory, size, description, completed, failed);
            // Register ownership before recording commands so even allocation failures cannot leave a
            // submitted image copy whose staging allocation is no longer tracked.
            frameImageReadbacks.add(readback);
            command = (CommandBufferImpl) begin("devtools-async-image-readback-" + image.label());
            command.transition(image, ImageViewDesc.full(), restoreAccess, GpuCommands.Access.TRANSFER_READ);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).imageOffset().set(0, 0, 0);
            region.get(0).imageExtent().set(width, height, 1);
            vkCmdCopyImageToBuffer(command.handle(), image.bits(), currentLayout(image.bits()), staging, region);
            command.transition(image, ImageViewDesc.full(), GpuCommands.Access.TRANSFER_READ, restoreAccess);
            command.end();
            return true;
        } catch (RuntimeException | Error error) {
            if (readback != null) {
                frameImageReadbacks.remove(readback);
            }
            if (command != null && recorded.remove(command)) {
                try {
                    command.free();
                } catch (RuntimeException cleanupError) {
                    recyclingFailures.add("Failed to discard an unsubmitted image readback command: " + cleanupError);
                }
            }
            if (staging != 0L) {
                vkDestroyBuffer(device.vkDevice(), staging, null);
            }
            if (memory != 0L) {
                vkFreeMemory(device.vkDevice(), memory, null);
            }
            try {
                failed.accept(error);
            } catch (RuntimeException ignored) {
                // A diagnostics callback must not escape into the render loop.
            }
            return true;
        }
    }

    private static GpuCommands.Access accessForTrackedLayout(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_GENERAL -> GpuCommands.Access.SHADER_WRITE;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> GpuCommands.Access.SHADER_READ;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> GpuCommands.Access.COLOR_ATTACHMENT;
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> GpuCommands.Access.TRANSFER_READ;
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> GpuCommands.Access.TRANSFER_WRITE;
            case VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> GpuCommands.Access.PRESENT;
            default -> null;
        };
    }

    @Override
    public String imageLayout(ImageHandle image) {
        if (image == null || image.isNull() || image.kind() != ImageHandle.Kind.VK_IMAGE) {
            return "UNKNOWN";
        }
        Integer layout = imageLayouts.get(image.bits());
        if (layout == null && externalImages.contains(image.bits())) {
            layout = VK_IMAGE_LAYOUT_GENERAL;
        }
        if (layout == null) {
            return "UNKNOWN";
        }
        return switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> "UNDEFINED";
            case VK_IMAGE_LAYOUT_GENERAL -> "GENERAL";
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> "SHADER_READ_ONLY_OPTIMAL";
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> "COLOR_ATTACHMENT_OPTIMAL";
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> "TRANSFER_SRC_OPTIMAL";
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> "TRANSFER_DST_OPTIMAL";
            case VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> "PRESENT_SRC_KHR";
            default -> "UNKNOWN(" + layout + ")";
        };
    }

    /**
     * Attach a completion fence after host-submitted frame commands without waiting on it. Call only at the
     * present boundary, after Minecraft has submitted the command buffers supplied by the loader.
     */
    public boolean finishHostFrameAsync(GpuQueue queue) {
        if (queue == null || queue.nativeQueue() == 0L) {
            return false;
        }
        boolean hasWork = !frameImageReadbacks.isEmpty() || !hostUploadLeases.isEmpty()
                || !pendingDescriptorSets.isEmpty();
        if (!hasWork) {
            return false;
        }
        // Prepare every Java-side object and list capacity before queue submission. After a successful
        // submit, throwing before tracking its fence would make the staging allocation unreclaimable.
        List<PendingImageReadback> frameReadbacks = new ArrayList<>(frameImageReadbacks);
        List<VulkanUploadArena.Lease> frameUploadLeases = new ArrayList<>(hostUploadLeases);
        pendingHostFrames.ensureCapacity(pendingHostFrames.size() + 1);
        long fence = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pFence = stack.mallocLong(1);
            int error = vkCreateFence(device.vkDevice(),
                    org.lwjgl.vulkan.VkFenceCreateInfo.calloc(stack).sType$Default(), null, pFence);
            if (error != VK_SUCCESS) {
                recyclingFailures.add("DevTools frame fence creation failed: " + VulkanDevice.resultName(error));
                return false;
            }
            fence = pFence.get(0);
            PendingHostFrame pendingFrame = new PendingHostFrame(fence, frameReadbacks,
                    frameUploadLeases, !frameReadbacks.isEmpty());
            org.lwjgl.vulkan.VkQueue vkQueue = new org.lwjgl.vulkan.VkQueue(queue.nativeQueue(), device.vkDevice());
            var submit = VkSubmitInfo.calloc(stack).sType$Default();
            error = vkQueueSubmit(vkQueue, submit, fence);
            if (error != VK_SUCCESS) {
                vkDestroyFence(device.vkDevice(), fence, null);
                recyclingFailures.add("DevTools frame fence submission failed: " + VulkanDevice.resultName(error));
                return false;
            }
            pendingHostFrames.add(pendingFrame);
            frameImageReadbacks.clear();
            hostUploadLeases.clear();
            return true;
        } catch (RuntimeException error) {
            if (fence != 0L) {
                // A successful queue submit is followed only by non-throwing list operations above.
                recyclingFailures.add("Could not register DevTools frame fence: " + error);
            }
            return false;
        }
    }

    /** Nonblocking fence poll. Staging memory and upload leases are retired only after a signaled fence. */
    public int pollHostFrameCompletions() {
        int completedCount = 0;
        for (var iterator = pendingHostFrames.iterator(); iterator.hasNext();) {
            PendingHostFrame frame = iterator.next();
            int status = vkGetFenceStatus(device.vkDevice(), frame.fence);
            if (status == VK_NOT_READY) {
                continue;
            }
            if (status != VK_SUCCESS && status != VK_ERROR_DEVICE_LOST) {
                recyclingFailures.add("DevTools frame fence poll failed: " + VulkanDevice.resultName(status));
                continue;
            }
            iterator.remove();
            if (status == VK_SUCCESS) {
                for (PendingImageReadback readback : frame.readbacks) {
                    deliverImageReadback(readback);
                }
            } else {
                for (PendingImageReadback readback : frame.readbacks) {
                    failImageReadback(readback, new IllegalStateException("Vulkan device was lost during capture"));
                }
            }
            for (VulkanUploadArena.Lease lease : frame.uploadLeases) {
                lease.close();
            }
            vkDestroyFence(device.vkDevice(), frame.fence, null);
            completedCount++;
        }
        return completedCount;
    }

    /** Whether capture data is awaiting a GPU completion event. */
    public boolean hasPendingImageCapture() {
        if (!frameImageReadbacks.isEmpty()) {
            return true;
        }
        return pendingHostFrames.stream().anyMatch(frame -> frame.captureFrame);
    }

    /** Called only after the host already proved queue completion; no wait is performed here. */
    public void retireHostFramesAfterIdle() {
        for (PendingHostFrame frame : new ArrayList<>(pendingHostFrames)) {
            for (PendingImageReadback readback : frame.readbacks) {
                deliverImageReadback(readback);
            }
            for (VulkanUploadArena.Lease lease : frame.uploadLeases) {
                lease.close();
            }
            vkDestroyFence(device.vkDevice(), frame.fence, null);
        }
        pendingHostFrames.clear();
        for (PendingImageReadback readback : new ArrayList<>(frameImageReadbacks)) {
            deliverImageReadback(readback);
        }
        frameImageReadbacks.clear();
    }

    private void deliverImageReadback(PendingImageReadback readback) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int error = vkMapMemory(device.vkDevice(), readback.memory, 0L, readback.size, 0, pData);
            if (error != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException("Image staging map failed: "
                        + VulkanDevice.resultName(error));
            }
            try {
                ByteBuffer mapped = MemoryUtil.memByteBuffer(pData.get(0), Math.toIntExact(readback.size));
                byte[] bytes = new byte[mapped.remaining()];
                mapped.get(bytes);
                int stride = Math.multiplyExact(readback.description.width(),
                        readback.description.format().bytesPerPixel());
                readback.completed.accept(new GpuCommands.ImageReadback(readback.description.format(),
                        readback.description.width(), readback.description.height(), stride,
                        ByteOrder.nativeOrder().toString(), "TIGHT_ROW_MAJOR_MIP0_LAYER0", bytes));
            } finally {
                vkUnmapMemory(device.vkDevice(), readback.memory);
            }
        } catch (RuntimeException | Error error) {
            notifyImageReadbackFailure(readback, error);
        } finally {
            vkDestroyBuffer(device.vkDevice(), readback.buffer, null);
            vkFreeMemory(device.vkDevice(), readback.memory, null);
        }
    }

    private void failImageReadback(PendingImageReadback readback, Throwable failure) {
        notifyImageReadbackFailure(readback, failure);
        vkDestroyBuffer(device.vkDevice(), readback.buffer, null);
        vkFreeMemory(device.vkDevice(), readback.memory, null);
    }

    private void notifyImageReadbackFailure(PendingImageReadback readback, Throwable failure) {
        try {
            readback.failed.accept(failure);
        } catch (RuntimeException ignored) {
            recyclingFailures.add("DevTools capture failure callback threw: " + ignored);
        }
    }

    /**
     * Reads an entire image with one staging buffer, copy command and submit/wait. Tightly packed rows use
     * bufferRowLength=0, indexed as (y*width+x)*bpp. Preserve the true old layout and record
     * TRANSFER_SRC_OPTIMAL afterward; callers must restore the layout needed by later consumers.
     */
    public byte[] readImageBlocking(long image, int vkFormat, int width, int height) {
        int bpp = bytesPerPixel(vkFormat);
        long size = (long) width * height * bpp;
        if (size <= 0 || size > Integer.MAX_VALUE) {
            throw new dev.luxloader.core.util.LuxException(tr("Frame capture dimensions exceed readback limits: ")
                    + width + "x" + height + "x" + bpp);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCreateInfo info = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            int err = vkCreateBuffer(device.vkDevice(), info, null, pBuf);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(tr("Failed to create full-frame readback buffer: ")
                        + VulkanDevice.resultName(err));
            }
            long staging = pBuf.get(0);
            long memory = 0L;
            try {
                org.lwjgl.vulkan.VkMemoryRequirements req = org.lwjgl.vulkan.VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(device.vkDevice(), staging, req);
                int typeIndex = findMemoryType(req.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                org.lwjgl.vulkan.VkMemoryAllocateInfo alloc = org.lwjgl.vulkan.VkMemoryAllocateInfo.calloc(stack)
                        .sType$Default()
                        .allocationSize(req.size())
                        .memoryTypeIndex(typeIndex);
                LongBuffer pMem = stack.mallocLong(1);
                err = vkAllocateMemory(device.vkDevice(), alloc, null, pMem);
                if (err != VK_SUCCESS) {
                    throw new dev.luxloader.core.util.LuxException(tr("Failed to allocate full-frame readback memory: ")
                            + VulkanDevice.resultName(err));
                }
                memory = pMem.get(0);
                vkBindBufferMemory(device.vkDevice(), staging, memory, 0);

                CommandBufferImpl cmd = (CommandBufferImpl) begin("image-readback");
                cmd.transitionKeepingContents(dev.luxloader.api.gpu.ImageHandle.vkImage(image, "image-readback"),
                        dev.luxloader.api.gpu.ImageViewDesc.full(),
                        dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_READ);

                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                region.get(0).imageSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                        .baseArrayLayer(0).layerCount(1);
                region.get(0).imageOffset().set(0, 0, 0);
                region.get(0).imageExtent().set(width, height, 1);
                vkCmdCopyImageToBuffer(cmd.handle(), image,
                        currentLayout(image), staging, region);
                cmd.end();
                submitAndWait(cmd, device.graphicsQueue());

                org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
                vkMapMemory(device.vkDevice(), memory, 0, size, 0, pData);
                ByteBuffer mapped = org.lwjgl.system.MemoryUtil.memByteBuffer(pData.get(0), (int) size);
                byte[] out = new byte[(int) size];
                mapped.get(out);
                vkUnmapMemory(device.vkDevice(), memory);
                return out;
            } finally {
                vkDestroyBuffer(device.vkDevice(), staging, null);
                if (memory != 0L) {
                    vkFreeMemory(device.vkDevice(), memory, null);
                }
            }
        }
    }

    /** Bytes per pixel for supported readback formats; reject others explicitly. */
    private static int bytesPerPixel(int vkFormat) {
        return switch (vkFormat) {
            case 37 -> 4;   // VK_FORMAT_R8G8B8A8_UNORM
            case 44 -> 4;   // VK_FORMAT_B8G8R8A8_UNORM
            case 97 -> 8;   // VK_FORMAT_R16G16B16A16_SFLOAT
            case 100 -> 4;  // VK_FORMAT_R32_SFLOAT
            case 103 -> 8;  // VK_FORMAT_R32G32_SFLOAT
            case 109 -> 16; // VK_FORMAT_R32G32B32A32_SFLOAT
            default -> throw new dev.luxloader.core.util.LuxException(
                    tr("Pixel readback does not support VkFormat: ") + vkFormat + tr(" (add its byte size explicitly when required; do not guess)"));
        };
    }

    ByteBuffer readBufferBlocking(long bufferHandle, long offset, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCreateInfo info = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            int err = vkCreateBuffer(device.vkDevice(), info, null, pBuf);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(tr("Failed to create readback buffer: ") + VulkanDevice.resultName(err));
            }
            long staging = pBuf.get(0);
            long memory = 0L;
            try {
                org.lwjgl.vulkan.VkMemoryRequirements req = org.lwjgl.vulkan.VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(device.vkDevice(), staging, req);
                int typeIndex = findMemoryType(req.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                org.lwjgl.vulkan.VkMemoryAllocateInfo alloc = org.lwjgl.vulkan.VkMemoryAllocateInfo.calloc(stack)
                        .sType$Default()
                        .allocationSize(req.size())
                        .memoryTypeIndex(typeIndex);
                LongBuffer pMem = stack.mallocLong(1);
                err = vkAllocateMemory(device.vkDevice(), alloc, null, pMem);
                if (err != VK_SUCCESS) {
                    throw new dev.luxloader.core.util.LuxException(tr("Failed to allocate readback memory: ")
                            + VulkanDevice.resultName(err));
                }
                memory = pMem.get(0);
                vkBindBufferMemory(device.vkDevice(), staging, memory, 0);

                CommandBufferImpl cmd = (CommandBufferImpl) begin("readback");
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.get(0).srcOffset(offset).dstOffset(0).size(size);
                vkCmdCopyBuffer(cmd.handle(), bufferHandle, staging, region);
                cmd.end();
                submitAndWait(cmd, device.graphicsQueue());

                org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
                vkMapMemory(device.vkDevice(), memory, 0, size, 0, pData);
                ByteBuffer src = MemoryUtil.memByteBuffer(pData.get(0), (int) size);
                ByteBuffer copy = MemoryUtil.memAlloc((int) size);
                copy.put(src).flip();
                vkUnmapMemory(device.vkDevice(), memory);
                return copy;
            } finally {
                if (memory != 0L) {
                    vkFreeMemory(device.vkDevice(), memory, null);
                }
                vkDestroyBuffer(device.vkDevice(), staging, null);
            }
        }
    }

    /**
     * Trace-only: report exactly what is being submitted, tagged with OUR identity.
     *
     * <p>Placed at the two places that actually call {@code vkQueueSubmit} -- <b>not</b> at
     * {@code flush()}. An earlier version instrumented only {@code flush()}, which made
     * {@code readPixelBlocking}'s buffer (submitted through {@code submitAndWait}) look like
     * an orphan that recorded a barrier and was never submitted. It was not an orphan: the
     * instrument covered one of the two submission primitives, and I read its silence as a
     * fact. Before adding a trace, ask how many paths can reach the state being observed.
     */
    private void traceSubmission(String path, java.util.List<?> buffers) {
        if (!TRACE_LAYOUTS) {
            return;
        }
        for (Object o : buffers) {
            if (!(o instanceof CommandBufferImpl b)) {
                continue;
            }
            StringBuilder imgs = new StringBuilder();
            for (Long v : b.tracedImages) {
                if (imgs.length() > 0) {
                    imgs.append(',');
                }
                imgs.append("0x").append(Long.toHexString(v));
            }
            System.out.println("[VK] submit #" + (++tracedSubmitSeq)
                    + " path=" + path
                    + " owner=" + b.ownerTag
                    + " label=" + b.label
                    + " ended=" + b.ended
                    + " barriers=" + b.tracedBarriers
                    + " images=[" + imgs + "]");
            b.tracedBarriers = 0;
            b.tracedImages.clear();
        }
    }

    /** Synchronous initialization submission; never call every frame. */
    void submitAndWait(CommandBufferImpl cmd, GpuQueue queue) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer cmds = stack.mallocPointer(1);
            cmds.put(0, cmd.handle().address());
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(cmds);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
            LongBuffer pFence = stack.mallocLong(1);
            vkCreateFence(device.vkDevice(), fenceInfo, null, pFence);
            long fence = pFence.get(0);
            try {
                org.lwjgl.vulkan.VkQueue vkQueue = new org.lwjgl.vulkan.VkQueue(
                        queue.nativeQueue(), device.vkDevice());
                traceSubmission("submitAndWait", java.util.List.of(cmd));
                vkQueueSubmit(vkQueue, submit, fence);
                vkWaitForFences(device.vkDevice(), fence, true, Long.MAX_VALUE);
            } finally {
                vkDestroyFence(device.vkDevice(), fence, null);
                cmd.free();
                // Remove synchronously submitted/freed buffers from recorded; otherwise a later flush would submit and free an invalid handle again.
                recorded.remove(cmd);
            }
        }
    }

    /**
     * Track layouts per image at device scope, not per command buffer. Restarting each buffer at UNDEFINED
     * can legally discard existing pixels without validation errors. Remove entries on image destruction
     * to prevent stale state after handle reuse. Track owned transitions only; the host controls its
     * external images.
     */
    private final Map<Long, Integer> imageLayouts = new LinkedHashMap<>();

    /**
     * Returns the layout actually used for explicit vkCmd* declarations. Host images remain GENERAL and
     * bypass owned-image transitions; declaring TRANSFER_* would be wrong and contaminate later state.
     * Owned images use tracked layout values; untracked host images use GENERAL.
     */
    private int currentLayout(long imageBits) {
        Integer tracked = imageLayouts.get(imageBits);
        return tracked != null ? tracked : VK_IMAGE_LAYOUT_GENERAL;
    }

    /**
     * Barrier tracing, gated on {@code -Dluxloader.debug.vulkan=true} (already set by the
     * build for the client run).
     *
     * <p>Added because a whole chain of reasoning about
     * {@code VUID-vkCmdDispatch-None-09600} was circling: the descriptor path hardcodes
     * {@code VK_IMAGE_LAYOUT_GENERAL} (see bindDescriptors), so the only way to tell whether
     * the promise was actually established is to see the barriers themselves. Before this,
     * the {@code [VK]} flag only covered {@code createComputePipeline} — turning it on told
     * you nothing about layouts, which made "go read the trace" a useless experiment.
     */
    private static final boolean TRACE_LAYOUTS = Boolean.getBoolean("luxloader.debug.vulkan");

    /** Monotonic submission counter. Buffer HANDLES are recycled by the driver (we have
     *  observed two logically different buffers sharing one handle), so a handle can never
     *  establish buffer identity. Ours can. */
    private long tracedSubmitSeq;

    /** Trace-only buffer identity counter. Lives on the outer class because a non-static
     *  inner class cannot declare a static field. */
    private static final java.util.concurrent.atomic.AtomicLong TRACED_BUFFER_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /** Trace-only: per-buffer barrier tally, printed at submit time. */
    private final java.util.Map<Long, long[]> tracedBarrierTally = new java.util.HashMap<>();

    /** Human-readable {@code VkImageLayout} for the trace above. */
    static String layoutName(int layout) {
        return switch (layout) {
            case 0 -> "UNDEFINED";
            case 1 -> "GENERAL";
            case 2 -> "COLOR_ATTACHMENT_OPTIMAL";
            case 3 -> "DEPTH_STENCIL_ATTACHMENT_OPTIMAL";
            case 4 -> "DEPTH_STENCIL_READ_ONLY_OPTIMAL";
            case 5 -> "SHADER_READ_ONLY_OPTIMAL";
            case 6 -> "TRANSFER_SRC_OPTIMAL";
            case 7 -> "TRANSFER_DST_OPTIMAL";
            case 8 -> "PREINITIALIZED";
            case 1000001002 -> "PRESENT_SRC_KHR";
            default -> "LAYOUT_" + layout;
        };
    }

    /**
     * Removes image layout state on destruction to prevent stale state after handle reuse.
     * @param vkImage native image
     */
    public void forgetImage(long vkImage) {
        if (vkImage != 0L) {
            if (TRACE_LAYOUTS) {
                // A forget means the image is going away. If this fires for an image we are
                // still using every frame, the driver is free to hand the same handle value
                // to a brand-new image -- and the validation layer's per-image layout state
                // is destroyed with the old one, resetting to UNDEFINED. Our device-level
                // table, which we feed barriers from, would not know that.
                System.out.println("[VK] forgetImage 0x" + Long.toHexString(vkImage)
                        + " (was " + layoutName(imageLayouts.getOrDefault(vkImage, 0)) + ")");
            }
            imageLayouts.remove(vkImage);
            externalImages.remove(vkImage);
            externalFormats.remove(vkImage);
        }
    }

    /**
     * Host-owned images must retain GENERAL. The host initializes them from UNDEFINED to GENERAL at
     * creation, then blitFromTexture assumes GENERAL without a source barrier. Do not transition them to
     * shader-read/transfer layouts or discard via Access.NONE. Historical first-use validation reports
     * remained unresolved by offline reproductions; do not attribute them to a layout mechanism without
     * evidence.
     */
    private final java.util.Set<Long> externalImages = new java.util.LinkedHashSet<>();

    /** Measured host image format; zero means unknown. */
    private final Map<Long, Integer> externalFormats = new LinkedHashMap<>();

    /**
     * Registers an external image with its observed format, never a pipeline/test default.
     * @param vkImage native image
     * @param vkFormat measured VkFormat, or zero if unknown
     */
    public void registerExternalImage(long vkImage, int vkFormat) {
        if (vkImage == 0L) {
            return;
        }
        externalImages.add(vkImage);
        imageLayouts.put(vkImage, VK_IMAGE_LAYOUT_GENERAL);
        if (vkFormat > 0) {
            externalFormats.put(vkImage, vkFormat);
        }
    }

    /** Measured host format, or zero if unregistered. */
    public int externalFormat(long vkImage) {
        Integer f = externalFormats.get(vkImage);
        return f == null ? 0 : f;
    }

    /** Whether the host owns the image and controls its layout. */
    public boolean isExternalImage(long vkImage) {
        return externalImages.contains(vkImage);
    }

    /** Creates a compute pipeline from caller-supplied SPIR-V. */
    long createComputePipeline(ComputePipelineDesc desc, byte[] spirv) {
        SpirvEntryPoints.require(spirv, desc.entryPoint(), 5);
        Long cached = computePipelines.get(desc.name());
        if (cached != null) {
            return cached;
        }
        boolean trace = Boolean.getBoolean("luxloader.debug.vulkan");
        if (trace) {
        }
        long module = createShaderModule(spirv);
        if (trace) {
            System.out.println(tr("[VK] 1/4 Shader module ") + spirv.length + tr(" bytes -> module=0x")
                    + Long.toHexString(module));
        }
        long setLayout = ensureDescriptorSetLayout(desc);
        if (trace) {
            System.out.println(tr("[VK] 2/4 Descriptor set layout -> setLayout=0x") + Long.toHexString(setLayout));
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(setLayout));

            // Declare push constant ranges in the pipeline layout. Nonzero pushes without matching stage ranges violate VUID 00369 even if pipeline creation succeeds; zero-byte smoke tests do not exercise this path.
            if (desc.pushConstants() > 0) {
                org.lwjgl.vulkan.VkPushConstantRange.Buffer ranges =
                        org.lwjgl.vulkan.VkPushConstantRange.calloc(1, stack);
                ranges.get(0)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .offset(0)
                        .size(desc.pushConstants());
                layoutInfo.pPushConstantRanges(ranges);
            }
            LongBuffer pLayout = stack.mallocLong(1);
            if (trace) {
                System.out.println(tr("[VK] 3/4 Creating pipeline layout (setLayout=0x")
                        + Long.toHexString(setLayout) + "）");
            }
            check(vkCreatePipelineLayout(device.vkDevice(), layoutInfo, null, pLayout), "vkCreatePipelineLayout");
            long createdLayout = pLayout.get(0);
            pipelineLayouts.put(desc.name(), createdLayout);
            if (trace) {
                System.out.println(tr("[VK] 3/4 Pipeline layout -> pipelineLayout=0x")
                        + Long.toHexString(createdLayout));
            }

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module)
                    .pName(stack.UTF8(desc.entryPoint()));
            // Keep the entry-point string alive through the call and record its address for crash diagnosis.
            long stageInfoAddress = stage.address();

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(createdLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            pPipeline.put(0, 0L);
            if (trace) {
                System.out.println(tr("[VK] 4/4 Calling vkCreateComputePipelines (entry point ")
                        + desc.entryPoint() + "，stage@0x" + Long.toHexString(stageInfoAddress) + "）");
            }
            int pipelineResult = vkCreateComputePipelines(device.vkDevice(), VK_NULL_HANDLE,
                    pipelineInfo, null, pPipeline);
            if (trace) {
                System.out.println(tr("[VK] 4/4 vkCreateComputePipelines returned ") + pipelineResult
                        + tr(", handle=0x") + Long.toHexString(pPipeline.get(0))
                        + "（VK_SUCCESS=" + org.lwjgl.vulkan.VK10.VK_SUCCESS + "）");
            }
            if (pipelineResult != org.lwjgl.vulkan.VK10.VK_SUCCESS) {
                device.reportIssue("compute_pipeline_create_failed",
                        tr("vkCreateComputePipelines failed: ") + VulkanDevice.resultName(pipelineResult)
                                + tr(" (pipeline ") + desc.name() + "）");
            }
            check(pipelineResult, "vkCreateComputePipelines");
            long pipeline = pPipeline.get(0);
            if (pipeline == 0L) {
                // Report shader module, pipeline layout and SPIR-V size when a driver returns success with a zero pipeline handle, and retain the device/driver-specific failure in its profile.
                device.reportIssue("compute_pipeline_null_handle",
                        tr("vkCreateComputePipelines succeeded but returned a zero handle (pipeline ") + desc.name()
                                + "，SPIR-V " + spirv.length + tr(" bytes) -- driver error"));
                throw new dev.luxloader.core.util.LuxException(
                        tr("vkCreateComputePipelines succeeded but returned a zero handle (pipeline: ") + desc.name()
                                + "；SPIR-V " + spirv.length + tr(" bytes, module=0x") + Long.toHexString(module)
                                + "，setLayout=0x" + Long.toHexString(setLayout)
                                + "，pipelineLayout=0x" + Long.toHexString(createdLayout)
                                + tr("VulkanCommands.a7d84c9ff6", ", entry point ") + desc.entryPoint()
                                + tr(", bindings ") + desc.bindings().size() + tr("VulkanCommands.f9d529eacd", " total")
                                + tr(", push constants ") + desc.pushConstants() + tr(" bytes.")
                                + tr(" If these are nonzero, check the driver; enable -Dluxloader.debug.vulkan=true for the full trace)"));
            }
            if (trace) {
                System.out.println(tr("[VK] 4/4 Compute pipeline ready pipeline=0x") + Long.toHexString(pipeline));
            }
            computePipelines.put(desc.name(), pipeline);
            return pipeline;
        }
    }

    long createGraphicsPipeline(GraphicsPipelineDesc desc, byte[] vertexSpirv, byte[] fragmentSpirv) {
        if (desc == null || vertexSpirv == null || fragmentSpirv == null) {
            throw new IllegalArgumentException("Graphics pipeline and shaders are required");
        }
        SpirvEntryPoints.require(vertexSpirv, desc.vertexEntry(), 0);
        SpirvEntryPoints.require(fragmentSpirv, desc.fragmentEntry(), 4);
        String key = "graphics:" + desc.name();
        Long cached = graphicsPipelines.get(key);
        if (cached != null) {
            return cached;
        }
        long vertexModule = createShaderModule(vertexSpirv);
        long fragmentModule = createShaderModule(fragmentSpirv);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long setLayout = ensureDescriptorSetLayout(key, desc.bindings(),
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            org.lwjgl.vulkan.VkPipelineLayoutCreateInfo layoutInfo =
                    org.lwjgl.vulkan.VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(setLayout));
            if (desc.pushConstants() > 0) {
                var ranges = org.lwjgl.vulkan.VkPushConstantRange.calloc(1, stack);
                ranges.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                        .offset(0).size(desc.pushConstants());
                layoutInfo.pPushConstantRanges(ranges);
            }
            LongBuffer layoutOut = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device.vkDevice(), layoutInfo, null, layoutOut),
                    "vkCreatePipelineLayout(graphics)");
            long layout = layoutOut.get(0);
            pipelineLayouts.put(key, layout);

            var stages = org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertexModule).pName(stack.UTF8(desc.vertexEntry()));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragmentModule).pName(stack.UTF8(desc.fragmentEntry()));

            var vertexInput = org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default();
            if (desc.vertexStride() > 0) {
                var binding = org.lwjgl.vulkan.VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(desc.vertexStride())
                        .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                vertexInput.pVertexBindingDescriptions(binding);
            }
            if (!desc.attributes().isEmpty()) {
                var attributes = org.lwjgl.vulkan.VkVertexInputAttributeDescription
                        .calloc(desc.attributes().size(), stack);
                for (int i = 0; i < desc.attributes().size(); i++) {
                    var attribute = desc.attributes().get(i);
                    attributes.get(i).location(attribute.location()).binding(0)
                            .format(attribute.format().vkFormat()).offset(attribute.offset());
                }
                vertexInput.pVertexAttributeDescriptions(attributes);
            }
            var assembly = org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            var viewport = org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default().viewportCount(1).scissorCount(1);
            int cull = switch (desc.cullMode()) {
                case NONE -> VK_CULL_MODE_NONE;
                case BACK -> VK_CULL_MODE_BACK_BIT;
                case FRONT -> VK_CULL_MODE_FRONT_BIT;
            };
            var raster = org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(cull)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f);
            var multisample = org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            int depthCompare = desc.depthCompare() == GraphicsPipelineDesc.DepthCompare.GREATER_OR_EQUAL
                    ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL;
            var depth = org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default().depthTestEnable(desc.depthTest())
                    .depthWriteEnable(desc.depthWrite()).depthCompareOp(depthCompare);
            var blendAttachment = org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.get(0).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(desc.alphaBlend())
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK_BLEND_OP_ADD);
            var blend = org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(blendAttachment);
            var dynamicStates = org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT,
                            VK_DYNAMIC_STATE_SCISSOR));
            var rendering = org.lwjgl.vulkan.VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType$Default().colorAttachmentCount(1)
                    .pColorAttachmentFormats(stack.ints(desc.colorFormat().vkFormat()))
                    .depthAttachmentFormat(desc.depthFormat().isDefined()
                            ? desc.depthFormat().vkFormat() : VK_FORMAT_UNDEFINED);
            var pipelineInfo = org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().pNext(rendering.address()).pStages(stages)
                    .pVertexInputState(vertexInput).pInputAssemblyState(assembly)
                    .pViewportState(viewport).pRasterizationState(raster)
                    .pMultisampleState(multisample).pDepthStencilState(depth)
                    .pColorBlendState(blend).pDynamicState(dynamicStates).layout(layout);
            LongBuffer pipelineOut = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device.vkDevice(), VK_NULL_HANDLE,
                    pipelineInfo, null, pipelineOut), "vkCreateGraphicsPipelines");
            long pipeline = pipelineOut.get(0);
            if (pipeline == 0L) {
                throw new dev.luxloader.core.util.LuxException(
                        "vkCreateGraphicsPipelines returned a null pipeline: " + desc.name());
            }
            graphicsPipelines.put(key, pipeline);
            return pipeline;
        }
    }

    /** Creates a shader module. */
    long createShaderModule(byte[] spirv) {
        if (spirv == null || spirv.length == 0) {
            throw new dev.luxloader.core.util.LuxException(tr("SPIR-V data is empty"));
        }
        SpirvKey key = new SpirvKey(spirv);
        Long cached = shaderModules.get(key);
        if (cached != null) {
            return cached;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer code = MemoryUtil.memAlloc(spirv.length);
            try {
                code.put(spirv).flip();
                VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default()
                        .pCode(code);
                LongBuffer pModule = stack.mallocLong(1);
                check(vkCreateShaderModule(device.vkDevice(), info, null, pModule), "vkCreateShaderModule");
                long module = pModule.get(0);
                shaderModules.put(key, module);
                return module;
            } finally {
                MemoryUtil.memFree(code);
            }
        }
    }

    private long ensureDescriptorSetLayout(ComputePipelineDesc desc) {
        return ensureDescriptorSetLayout(desc.name(), desc.bindings(), VK_SHADER_STAGE_COMPUTE_BIT);
    }

    private long ensureDescriptorSetLayout(String key, List<ComputePipelineDesc.Binding> bindings,
                                           int stages) {
        Long cached = descriptorSetLayouts.get(key);
        if (cached != null) {
            return cached;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default();
            if (!bindings.isEmpty()) {
                VkDescriptorSetLayoutBinding.Buffer vkBindings =
                        VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
                for (int i = 0; i < bindings.size(); i++) {
                    ComputePipelineDesc.Binding b = bindings.get(i);
                    vkBindings.get(i)
                            .binding(b.binding())
                            .descriptorType(descriptorTypeOf(b.type()))
                            .descriptorCount(b.count())
                            .stageFlags(stages);
                }
                info.pBindings(vkBindings);
            }
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device.vkDevice(), info, null, pLayout),
                    "vkCreateDescriptorSetLayout");
            long layout = pLayout.get(0);
            descriptorSetLayouts.put(key, layout);
            return layout;
        }
    }

    private static int descriptorTypeOf(ComputePipelineDesc.DescriptorType type) {
        return switch (type) {
            case COMBINED_IMAGE_SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case SAMPLED_IMAGE, SAMPLED_IMAGE_ARRAY -> VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            case STORAGE_IMAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case ACCELERATION_STRUCTURE -> 1000150000; // VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR
        };
    }

    /**
     * Creates/caches a default linear clamp-to-edge sampler for screen-space scaling. Plugins needing
     * different anisotropy/mip behavior may create their own native sampler.
     */
    long ensureSampler(boolean nearest) {
        String key = nearest ? "nearest-clamp" : "linear-clamp";
        Long cached = samplers.get(key);
        if (cached != null) {
            return cached;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkSamplerCreateInfo info = org.lwjgl.vulkan.VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(nearest ? VK_FILTER_NEAREST : VK_FILTER_LINEAR)
                    .minFilter(nearest ? VK_FILTER_NEAREST : VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .anisotropyEnable(false)
                    .maxLod(0f)
                    .minLod(0f)
                    .compareEnable(false)
                    .unnormalizedCoordinates(false);
            java.nio.LongBuffer pSampler = stack.mallocLong(1);
            check(vkCreateSampler(device.vkDevice(), info, null, pSampler), "vkCreateSampler");
            long sampler = pSampler.get(0);
            samplers.put(key, sampler);
            return sampler;
        }
    }

    // Pages remain alive until the GPU-completed frame boundary. Growing a pool
    // must never reset sets already referenced by recorded or submitted draws.
    private long createDescriptorPool(List<ComputePipelineDesc.Binding> bindings) {
        java.util.Map<Integer, Integer> countByType = new LinkedHashMap<>();
        for (ComputePipelineDesc.Binding b : bindings) {
            countByType.merge(descriptorTypeOf(b.type()), Math.max(1, b.count()), Integer::sum);
        }
        if (countByType.isEmpty()) {
            countByType.put(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(countByType.size(), stack);
            int i = 0;
            for (var e : countByType.entrySet()) {
                sizes.get(i++).type(e.getKey()).descriptorCount(Math.multiplyExact(e.getValue(), DESCRIPTOR_SETS_PER_PAGE));
            }
            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(DESCRIPTOR_SETS_PER_PAGE)
                    .pPoolSizes(sizes);
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateDescriptorPool(device.vkDevice(), info, null, pPool), "vkCreateDescriptorPool");
            long created = pPool.get(0);
            return created;
        }
    }

    private long allocateDescriptorSet(String key, List<ComputePipelineDesc.Binding> bindings,
                                       long layout, MemoryStack stack) {
        var pools = descriptorPools.computeIfAbsent(key, ignored -> new DescriptorPoolPages());
        var alloc = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(layout));
        LongBuffer result = stack.mallocLong(1);
        while (true) {
            if (pools.current == pools.pages.size()) {
                pools.pages.add(new DescriptorPoolPage(createDescriptorPool(bindings)));
            }
            var page = pools.pages.get(pools.current);
            if (page.allocatedSets == DESCRIPTOR_SETS_PER_PAGE) {
                pools.current++;
                continue;
            }
            alloc.descriptorPool(page.handle);
            int error = vkAllocateDescriptorSets(device.vkDevice(), alloc, result);
            if (error == VK_SUCCESS) {
                page.allocatedSets++;
                long set = result.get(0);
                pendingDescriptorSets.add(set);
                return set;
            }
            boolean exhausted = error == org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY
                    || error == VK_ERROR_FRAGMENTED_POOL;
            if (exhausted && page.allocatedSets > 0) {
                pools.current++;
                continue;
            }
            // An empty correctly sized page failing is not cured by unbounded retries.
            throw new dev.luxloader.core.util.LuxException(tr("Failed to allocate descriptor set: ")
                    + VulkanDevice.resultName(error) + tr(" (pipeline ") + key
                    + tr(", pool page ") + (pools.current + 1) + tr(", allocated ") + page.allocatedSets + "）");
        }
    }

    /** Reclaims frame descriptor sets by resetting pools at a safe frame boundary, avoiding per-set free overhead. */
    public void resetDescriptorPools() {
        // External host command buffers can still reference these sets until their fence signals.
        if (!pendingHostFrames.isEmpty() || !frameImageReadbacks.isEmpty()) {
            return;
        }
        // The host reaches this boundary only after its submitted frame completes.
        // Upload-only frames also need reclamation, even without descriptor sets.
        for (var lease : hostUploadLeases) lease.close();
        hostUploadLeases.clear();
        uploadArena.report();
        collectPassTimings();
        timingFrame++;
        synchronized (pendingDescriptorSets) {
            if (pendingDescriptorSets.isEmpty()) {
                return;
            }
            for (var pools : descriptorPools.values()) {
                for (var page : pools.pages) {
                    check(vkResetDescriptorPool(device.vkDevice(), page.handle, 0), "vkResetDescriptorPool");
                    page.allocatedSets = 0;
                }
                pools.current = 0;
            }
            pendingDescriptorSets.clear();
        }
    }

    private int findMemoryType(int typeBits, int required) {
        for (int i = 0; i < device.memoryProperties().memoryTypeCount(); i++) {
            boolean typeOk = (typeBits & (1 << i)) != 0;
            boolean propsOk = (device.memoryProperties().memoryTypes(i).propertyFlags() & required) == required;
            if (typeOk && propsOk) {
                return i;
            }
        }
        throw new dev.luxloader.core.util.LuxException(tr("No usable GPU memory type"));
    }

    private static void check(int result, String what) {        if (result != VK_SUCCESS) {
            throw new dev.luxloader.core.util.LuxException(what + tr(" failed: ") + VulkanDevice.resultName(result));
        }
    }

    /** Tracks buffer-to-memory ownership for cleanup. */
    void trackBuffer(long buffer, long memory) {
        bufferMemory.put(buffer, memory);
    }

    /**
     * Installs the provider's image-view resolver.
     * @param resolver maps VkImage to VkImageView, or zero when unavailable
     */
    public void setViewResolver(java.util.function.LongFunction<Long> resolver) {
        this.viewResolver = resolver;
    }

    /** Guards close(): the host hook and the loader close path both call it. */
    private boolean closed;

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        uploadArena.close();
        hostUploadLeases.clear();
        // Pipeline layouts were never destroyed anywhere in this repository, yet
        // every VUID-vkDestroyDevice-device-05137 leak report named
        // "VkPipelineLayout". Destroy them along with everything else.
        for (long layout : pipelineLayouts.values()) {
            vkDestroyPipelineLayout(device.vkDevice(), layout, null);
        }
        pipelineLayouts.clear();
        for (long pipeline : computePipelines.values()) {
            vkDestroyPipeline(device.vkDevice(), pipeline, null);
        }
        computePipelines.clear();
        for (long pipeline : graphicsPipelines.values()) {
            vkDestroyPipeline(device.vkDevice(), pipeline, null);
        }
        graphicsPipelines.clear();
        for (long module : shaderModules.values()) {
            vkDestroyShaderModule(device.vkDevice(), module, null);
        }
        shaderModules.clear();
        for (long layout : descriptorSetLayouts.values()) {
            vkDestroyDescriptorSetLayout(device.vkDevice(), layout, null);
        }
        descriptorSetLayouts.clear();
        for (var pools : descriptorPools.values()) {
            for (var page : pools.pages) {
                vkDestroyDescriptorPool(device.vkDevice(), page.handle, null);
            }
        }
        descriptorPools.clear();
        for (long sampler : samplers.values()) {
            vkDestroySampler(device.vkDevice(), sampler, null);
        }
        samplers.clear();
        for (long semaphore : createdObjects) {
            vkDestroySemaphore(device.vkDevice(), semaphore, null);
        }
        createdObjects.clear();
        // Clear device-owned layout state on teardown to prevent stale handle reuse. Destroy only pooled fences whose completion is known; pending submissions may still reference their fences.
        if (!pendingSubmissions.isEmpty()) {
            recyclingFailures.add(tr("Shutdown still has ") + pendingSubmissions.size()
                    + tr(" in-flight batches; retaining their fences to avoid undefined behavior"));
            pendingSubmissions.clear();
        }
        for (long fence : spareFences) {
            vkDestroyFence(device.vkDevice(), fence, null);
        }
        spareFences.clear();
        if (passTimingPool != 0L) {
            vkDestroyQueryPool(device.vkDevice(), passTimingPool, null);
            passTimingPool = 0;
        }
        if (timestampQueryPool != 0L) {
            vkDestroyQueryPool(device.vkDevice(), timestampQueryPool, null);
            timestampQueryPool = 0L;
        }
        imageLayouts.clear();
        vkDestroyCommandPool(device.vkDevice(), pool, null);
    }

    /**
     * Command buffer implementation managing declared resource access and required synchronization while
     * retaining explicit barriers for advanced use.
     */
    final class CommandBufferImpl implements CommandBuffer {

        private final VkCommandBuffer cmd;
        private final String label;
        private final long cmdPool;
        // Layouts are tracked at device scope; see VulkanCommands.imageLayouts.
        private final Map<String, Long> boundImages = new LinkedHashMap<>();
        private final Map<String, java.util.List<ImageHandle>> boundImageArrays = new LinkedHashMap<>();
        private final Map<String, long[]> boundBuffers = new LinkedHashMap<>();
        private final Map<String, Long> boundAccel = new LinkedHashMap<>();
        private long boundPipeline;
        private int boundBindPoint = -1;
        private ComputePipelineDesc activePipelineDesc;
        private GraphicsPipelineDesc activeGraphicsDesc;
        private long pipelineLayout;
        private boolean inRenderPass;
        // Valid only for consecutive graphics draws with unchanged resource bindings.
        private boolean graphicsDescriptorsBound;
        private boolean indexBufferBound;
        private boolean ended;
        private VulkanUploadArena.Lease uploadLease;
        private byte[] pendingPushConstants;
        private final List<String> diagnosticsNotes = new ArrayList<>();

        CommandBufferImpl(VkCommandBuffer cmd, String label, long cmdPool) {
            this.cmd = cmd;
            this.label = label;
            this.cmdPool = cmdPool;
        }

        VkCommandBuffer handle() {
            return cmd;
        }

        @Override
        public CommandBuffer recordNativeVulkan(java.util.function.Consumer<NativeVulkanContext> record) {
            java.util.Objects.requireNonNull(record, "record");
            if (ended || inRenderPass) {
                throw new IllegalStateException("Native Vulkan commands require an open buffer outside a render pass");
            }
            record.accept(new NativeVulkanContext(device.nativeInstanceHandle(),
                    device.physicalDevice().address(), device.vkDevice().address(),
                    cmd.address(), device.graphicsQueue().familyIndex()));
            return this;
        }

        /**
         * Whether end() made this buffer executable. Recording buffers must never be submitted;
         * dropUnterminated removes failed unfinished recordings.
         */
        boolean hasEnded() {
            return ended;
        }

        @Override
        public CommandBuffer transition(ImageHandle image, ImageViewDesc view, Access from, Access to) {
            if (image == null || image.isNull()) {
                return this;
            }
            // Host images stay in GENERAL. A same-layout memory barrier makes our
            // writes visible when the host's next command buffer reads the image.
            if (externalImages.contains(image.bits())) {
                if (from != Access.NONE && to != Access.NONE) {
                    // Keep the host's GENERAL layout, but synchronize our transfer
                    // write before the host's following copy or blit reads it.
                    insertImageBarrier(image.bits(), VK_IMAGE_LAYOUT_GENERAL,
                            VK_IMAGE_LAYOUT_GENERAL, accessMaskOf(from),
                            accessMaskOf(to), view);
                }
                return this;
            }
            int oldLayout = layoutOf(from);
            int newLayout = layoutOf(to);
            // Access.NONE means "I do not care about this image's previous contents". The ONLY
            // legal way to say that in Vulkan is oldLayout = VK_IMAGE_LAYOUT_UNDEFINED, and
            // layoutOf(NONE) is exactly that. Overriding it with the device-level table turn a
            // discard into a CLAIM -- "it was in X" -- which is:
            //   * a claim the validation layer may not accept, and
            //   * when rejected, the transition is dropped and the image never gets an entry
            //     in that command buffer's layout map, so the next descriptor check reports
            //     "expects GENERAL -- instead, current layout is UNDEFINED".
            // This is not masking: an image that a pass fully rewrites has no defined contents
            // at frame start, so UNDEFINED is the true statement. Callers that DO need the
            // previous contents pass something other than NONE (e.g. blitImage passes
            // TRANSFER_READ) and still get the table value.
            Integer current = imageLayouts.get(image.bits());
            if (current != null && from != Access.NONE) {
                oldLayout = current;
            }
            // An explicit transition also declares a memory dependency. GENERAL ->
            // GENERAL must publish storage writes to the next dispatch's reads/writes.
            insertImageBarrier(image.bits(), oldLayout, newLayout,
                    accessMaskOf(from), accessMaskOf(to), view);
            imageLayouts.put(image.bits(), newLayout);
            return this;
        }

        /**
         * Transitions while preserving contents using the device-level actual layout, falling back to
         * UNDEFINED only when unknown. Access.NONE intentionally discards contents and is invalid for a
         * freshly computed presentation source. Include conservative possible-writer source access masks so
         * later readers observe prior writes.
         */
        private void transitionKeepingContents(ImageHandle image, ImageViewDesc view, Access to) {
            if (image == null || image.isNull()) {
                return;
            }
            // The host keeps its images in GENERAL. Preserve the layout, but make its
            // prior writes visible to this command buffer's read after the host submits.
            if (externalImages.contains(image.bits())) {
                long writes = accessMaskOf(Access.COLOR_ATTACHMENT)
                        | accessMaskOf(Access.DEPTH_STENCIL)
                        | accessMaskOf(Access.SHADER_WRITE)
                        | accessMaskOf(Access.TRANSFER_WRITE);
                insertImageBarrier(image.bits(), VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_GENERAL, writes, accessMaskOf(to), view);
                return;
            }
            Integer tracked = imageLayouts.get(image.bits());
            int oldLayout = tracked == null ? VK_IMAGE_LAYOUT_UNDEFINED : tracked;
            int newLayout = layoutOf(to);
            if (oldLayout == newLayout && to == Access.SHADER_READ) return;
            long srcAccess = accessMaskOf(Access.SHADER_WRITE)
                    | accessMaskOf(Access.DEPTH_STENCIL)
                    | accessMaskOf(Access.TRANSFER_WRITE)
                    | accessMaskOf(Access.COLOR_ATTACHMENT);
            insertImageBarrier(image.bits(), oldLayout, newLayout,
                    srcAccess, accessMaskOf(to), view);
            imageLayouts.put(image.bits(), newLayout);
        }

        @Override
        public CommandBuffer clearColor(ImageHandle image, ImageViewDesc view, float r, float g, float b, float a) {
            if (image == null || image.isNull()) {
                return this;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                transition(image, view, Access.NONE, Access.TRANSFER_WRITE);
                VkClearColorValue color = VkClearColorValue.calloc(stack);
                color.float32(0, r).float32(1, g).float32(2, b).float32(3, a);
                VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
                        .baseArrayLayer(0).layerCount(VK_REMAINING_ARRAY_LAYERS);
                vkCmdClearColorImage(cmd, image.bits(), currentLayout(image.bits()), color, range);
                // Track transitions only for owned images; host images remain GENERAL and must not acquire false TRANSFER_* state.
                if (!externalImages.contains(image.bits())) {
                    imageLayouts.put(image.bits(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                }
            }
            return this;
        }

        @Override
        public CommandBuffer blitImage(ImageHandle source, ImageViewDesc sourceView,
                                       int sourceWidth, int sourceHeight,
                                       ImageHandle target, ImageViewDesc targetView,
                                       int targetWidth, int targetHeight) {
            if (source == null || source.isNull() || target == null || target.isNull()) {
                return this;
            }
            if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
                // Reject zero-sized blits with an actionable source/target dimension error before invoking Vulkan.
                throw new IllegalArgumentException(tr("Blit dimensions must be positive: source ")
                        + sourceWidth + "x" + sourceHeight
                        + tr(", destination ") + targetWidth + "x" + targetHeight);
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Preserve source contents with transitionKeepingContents. transition(NONE,...) bypasses tracked state and declares UNDEFINED, permitting discard. The owned destination can discard old contents because this blit replaces its full extent.
                transitionKeepingContents(source, sourceView, Access.TRANSFER_READ);
                transition(target, targetView, Access.NONE, Access.TRANSFER_WRITE);

                VkImageBlit.Buffer regions = VkImageBlit.calloc(1, stack);
                VkImageBlit region = regions.get(0);
                region.srcSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.dstSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.srcOffsets(0).set(0, 0, 0);
                region.srcOffsets(1).set(sourceWidth, sourceHeight, 1);
                region.dstOffsets(0).set(0, 0, 0);
                region.dstOffsets(1).set(targetWidth, targetHeight, 1);

                // Use linear filtering for scaled presentation and declare each side's actual layout. Host targets remain GENERAL, not TRANSFER_DST_OPTIMAL.
                vkCmdBlitImage(cmd,
                        source.bits(), currentLayout(source.bits()),
                        target.bits(), currentLayout(target.bits()),
                        regions, VK_FILTER_LINEAR);

                // Track only owned image transitions; recording host images as TRANSFER_* would corrupt later layout assumptions.
                if (!externalImages.contains(source.bits())) {
                    imageLayouts.put(source.bits(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                }
                if (!externalImages.contains(target.bits())) {
                    imageLayouts.put(target.bits(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                }
            }
            return this;
        }

        @Override
        public CommandBuffer beginRenderPass(RenderPassDesc pass) {
            graphicsDescriptorsBound = false;
            if (pass == null) {
                throw new IllegalArgumentException(tr("Pass must not be null"));
            }
            if (inRenderPass) {
                throw new IllegalStateException("A render pass is already active");
            }
            if (pass.viewportW() <= 0 || pass.viewportH() <= 0) {
                throw new IllegalArgumentException("A graphics render pass needs an explicit viewport");
            }
            for (var attachment : pass.colors()) {
                if (attachment.load() == RenderPassDesc.LoadOp.LOAD) {
                    transitionKeepingContents(attachment.image(), attachment.view(), Access.COLOR_ATTACHMENT);
                } else {
                    transition(attachment.image(), attachment.view(), Access.NONE, Access.COLOR_ATTACHMENT);
                }
            }
            if (pass.depth() != null) {
                if (pass.depth().load() == RenderPassDesc.LoadOp.LOAD) {
                    transitionKeepingContents(pass.depth().image(), pass.depth().view(), Access.DEPTH_STENCIL);
                } else {
                    transition(pass.depth().image(), pass.depth().view(), Access.NONE, Access.DEPTH_STENCIL);
                }
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var colors = org.lwjgl.vulkan.VkRenderingAttachmentInfo
                        .calloc(pass.colors().size(), stack);
                for (int i = 0; i < pass.colors().size(); i++) {
                    var attachment = pass.colors().get(i);
                    long view = viewResolver == null ? 0L
                            : viewResolver.apply(attachment.image().bits());
                    if (view == 0L) {
                        throw new IllegalStateException("Color attachment has no image view: "
                                + attachment.image().label());
                    }
                    var info = colors.get(i).sType$Default().imageView(view)
                            .imageLayout(externalImages.contains(attachment.image().bits())
                                    ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                            .loadOp(loadOp(attachment.load()))
                            .storeOp(storeOp(attachment.store()));
                    float[] rgba = attachment.clearRgba();
                    for (int component = 0; component < 4; component++) {
                        info.clearValue().color().float32(component, rgba[component]);
                    }
                }
                var rendering = org.lwjgl.vulkan.VkRenderingInfo.calloc(stack)
                        .sType$Default().layerCount(1).pColorAttachments(colors);
                rendering.renderArea().offset().set(0, 0);
                rendering.renderArea().extent().set(pass.viewportW(), pass.viewportH());
                if (pass.depth() != null) {
                    var attachment = pass.depth();
                    long view = viewResolver == null ? 0L
                            : viewResolver.apply(attachment.image().bits());
                    if (view == 0L) {
                        throw new IllegalStateException("Depth attachment has no image view: "
                                + attachment.image().label());
                    }
                    var depthInfo = org.lwjgl.vulkan.VkRenderingAttachmentInfo.calloc(stack)
                            .sType$Default().imageView(view)
                            .imageLayout(externalImages.contains(attachment.image().bits())
                                    ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                            .loadOp(loadOp(attachment.load()))
                            .storeOp(storeOp(attachment.store()));
                    depthInfo.clearValue().depthStencil().depth(attachment.clearDepth());
                    rendering.pDepthAttachment(depthInfo);
                }
                var capabilities = device.vkDevice().getCapabilities();
                if (capabilities.vkCmdBeginRendering != 0L) {
                    vkCmdBeginRendering(cmd, rendering);
                } else if (capabilities.vkCmdBeginRenderingKHR != 0L) {
                    if (!dynamicRenderingExtensionNoted) {
                        device.diagnostics().info(tr("Host device uses VK_KHR_dynamic_rendering entry points"));
                        dynamicRenderingExtensionNoted = true;
                    }
                    vkCmdBeginRenderingKHR(cmd, rendering);
                } else {
                    throw new IllegalStateException(tr("Host Vulkan device exposes no dynamic rendering entry point")
                            + "（vkCmdBeginRendering / vkCmdBeginRenderingKHR）");
                }
                var viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack);
                viewport.get(0).x(0f).y(0f).width(pass.viewportW())
                        .height(pass.viewportH()).minDepth(0f).maxDepth(1f);
                vkCmdSetViewport(cmd, 0, viewport);
                var scissor = org.lwjgl.vulkan.VkRect2D.calloc(1, stack);
                scissor.get(0).offset().set(0, 0);
                scissor.get(0).extent().set(pass.viewportW(), pass.viewportH());
                vkCmdSetScissor(cmd, 0, scissor);
            }
            inRenderPass = true;
            diagnosticsNotes.add("renderPass(" + pass.name() + ")");
            return this;
        }

        private int loadOp(RenderPassDesc.LoadOp op) {
            return switch (op) {
                case LOAD -> VK_ATTACHMENT_LOAD_OP_LOAD;
                case CLEAR -> VK_ATTACHMENT_LOAD_OP_CLEAR;
                case DONT_CARE -> VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            };
        }

        private int storeOp(RenderPassDesc.StoreOp op) {
            return op == RenderPassDesc.StoreOp.STORE
                    ? VK_ATTACHMENT_STORE_OP_STORE : VK_ATTACHMENT_STORE_OP_DONT_CARE;
        }

        @Override
        public CommandBuffer bindComputePipeline(ComputePipelineDesc pipeline) {
            Long handle = computePipelines.get(pipeline.name());
            if (handle == null) {
                throw new dev.luxloader.core.util.LuxException(
                        tr("Compute pipeline has not been created: ") + pipeline.name() + tr(" (create it with VulkanDevice#createComputePipeline first)"));
            }
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, handle);
            boundPipeline = handle;
            boundBindPoint = VK_PIPELINE_BIND_POINT_COMPUTE;
            activePipelineDesc = pipeline;
            activeGraphicsDesc = null;
            pipelineLayout = pipelineLayouts.getOrDefault(pipeline.name(), pipelineLayout);
            return this;
        }

        @Override
        public CommandBuffer bindGraphicsPipeline(GraphicsPipelineDesc pipeline) {
            graphicsDescriptorsBound = false;
            if (!inRenderPass) {
                throw new IllegalStateException("Bind a graphics pipeline inside a render pass");
            }
            String key = "graphics:" + pipeline.name();
            Long handle = graphicsPipelines.get(key);
            if (handle == null) {
                throw new IllegalStateException("Graphics pipeline has not been created: " + pipeline.name());
            }
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
            boundPipeline = handle;
            boundBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
            activeGraphicsDesc = pipeline;
            activePipelineDesc = null;
            pipelineLayout = pipelineLayouts.get(key);
            return this;
        }

        @Override
        public CommandBuffer bindVertexBuffer(GpuDevice.Handle buffer, long offset) {
            if (buffer == null || buffer.isNull() || offset < 0) {
                throw new IllegalArgumentException("Invalid vertex buffer or offset");
            }
            vkCmdBindVertexBuffers(cmd, 0, new long[] {buffer.bits()}, new long[] {offset});
            return this;
        }

        @Override
        public CommandBuffer bindIndexBuffer(GpuDevice.Handle buffer, long offset) {
            if (buffer == null || buffer.isNull() || offset < 0) {
                throw new IllegalArgumentException("Invalid index buffer or offset");
            }
            vkCmdBindIndexBuffer(cmd, buffer.bits(), offset, VK_INDEX_TYPE_UINT32);
            indexBufferBound = true;
            return this;
        }

        @Override
        public CommandBuffer draw(int vertexCount, int instanceCount, int firstVertex,
                                  int firstInstance) {
            requireGraphicsDraw(vertexCount, instanceCount);
            vkCmdDraw(cmd, vertexCount, instanceCount, firstVertex, firstInstance);
            return this;
        }

        @Override
        public CommandBuffer drawIndexed(int indexCount, int instanceCount, int firstIndex,
                                         int vertexOffset, int firstInstance) {
            requireGraphicsDraw(indexCount, instanceCount);
            if (!indexBufferBound) {
                throw new IllegalStateException("Bind an index buffer before drawIndexed");
            }
            vkCmdDrawIndexed(cmd, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
            return this;
        }

        private void requireGraphicsDraw(int count, int instances) {
            if (!inRenderPass || boundBindPoint != VK_PIPELINE_BIND_POINT_GRAPHICS
                    || count <= 0 || instances <= 0) {
                throw new IllegalStateException("Draw requires an active pass, graphics pipeline, and counts");
            }
            if (pendingPushConstants != null && pendingPushConstants.length > 0) {
                ByteBuffer data = MemoryUtil.memAlloc(pendingPushConstants.length);
                try {
                    data.put(pendingPushConstants).flip();
                    vkCmdPushConstants(cmd, pipelineLayout,
                            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, data);
                } finally {
                    MemoryUtil.memFree(data);
                }
                pendingPushConstants = null;
            }
            if (!graphicsDescriptorsBound && activeGraphicsDesc != null
                    && !activeGraphicsDesc.bindings().isEmpty()) {
                bindDescriptors(activeGraphicsDesc.bindings(), "graphics:" + activeGraphicsDesc.name(),
                        VK_PIPELINE_BIND_POINT_GRAPHICS,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
                graphicsDescriptorsBound = true;
            }
        }

        @Override
        public CommandBuffer writeImage(String name, ImageHandle image, ImageViewDesc view) {
            if (inRenderPass) {
                throw new IllegalStateException("Bind image descriptors before beginning the render pass");
            }
            if (image == null || image.isNull()) {
                return this;
            }
            boundImages.put(name, image.bits());
            graphicsDescriptorsBound = false;
            if (view != null && !view.asStorage()) {
                // A sampled image is an input. In particular, the RT history image was
                // written in the previous frame and its pixels must survive this transition.
                // Access.NONE below declares oldLayout=UNDEFINED and permits discarding them;
                // averaging that discarded history makes the picture fade toward black.
                // Keep the earlier discard path for storage outputs, which are fully rewritten.
                transitionKeepingContents(image, view, Access.SHADER_READ);
                return this;
            }
            // Binding a storage descriptor does not imply overwriting the image.
            // Lighting stages read/modify preceding results, and history banks are
            // rebound across frames. Only an explicit transition from NONE may
            // discard contents; descriptor binding must preserve them.
            transitionKeepingContents(image, view, Access.SHADER_WRITE);
            return this;
        }

        @Override
        public CommandBuffer writeBuffer(String name, GpuDevice.Handle buffer, long offset, long size) {
            if (buffer == null || buffer.isNull()) {
                return this;
            }
            boundBuffers.put(name, new long[] {buffer.bits(), offset, size});
            graphicsDescriptorsBound = false;
            return this;
        }

        @Override
        public CommandBuffer writeImages(String name, java.util.List<ImageHandle> images,
                                         java.util.List<ImageViewDesc> views) {
            if (ended || inRenderPass) throw new IllegalStateException("Bind images outside a render pass");
            if (images == null || images.isEmpty() || views == null || views.size() != images.size()) {
                throw new IllegalArgumentException("Image array and views must have equal nonzero length");
            }
            for (int i = 0; i < images.size(); i++) {
                var image = images.get(i);
                if (image == null || image.isNull() || views.get(i).asStorage()) {
                    throw new IllegalArgumentException("Sampled image arrays need valid sampled views");
                }
                transitionKeepingContents(image, views.get(i), Access.SHADER_READ);
            }
            boundImageArrays.put(name, java.util.List.copyOf(images));
            graphicsDescriptorsBound = false;
            return this;
        }

        @Override
        public CommandBuffer updateBuffer(GpuDevice.Handle buffer, long offset, byte[] data) {
            if (data != null && data.length > 65536) throw new IllegalArgumentException("Use updateBuffers for large uploads");
            return updateBuffers(List.of(new GpuCommands.BufferUpdate(buffer, offset, data)));
        }

        @Override
        public CommandBuffer updateBuffers(List<GpuCommands.BufferUpdate> updates) {
            if (ended || inRenderPass) throw new IllegalStateException("Buffer update outside recording");
            if (updates.isEmpty()) return this;
            // Validate the complete batch before recording any GPU commands.
            long totalBytes = 0;
            for (var update : updates) {
                java.util.Objects.requireNonNull(update, "update");
                if (update.buffer().kind() != GpuDevice.Handle.Kind.VK_BUFFER
                        || update.data().length > update.buffer().size()
                        || update.offset() > update.buffer().size() - update.data().length) {
                    throw new IllegalArgumentException("Invalid buffer update range");
                }
                totalBytes += update.data().length;
            }
            boolean staged = totalBytes > 65536;
            var written = new HashMap<Long, java.util.TreeMap<Long, Long>>();
            int uploadSize = staged ? 0 : updates.stream().mapToInt(u -> u.data().length).max().orElseThrow();
            ByteBuffer upload = staged ? null : MemoryUtil.memAlloc(uploadSize);
            if (staged && uploadLease == null) {
                uploadLease = uploadArena.lease();
                // Keep both successful and interrupted host recordings until the
                // host completion boundary. Host-owned command pools are external.
                if (cmdPool == 0L) hostUploadLeases.add(uploadLease);
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack);
                var uploadRegion = staged ? VkBufferCopy.calloc(1, stack) : null;
                barrier.get(0).sType$Default()
                        .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, barrier, null, null);
                for (var update : updates) {
                    long begin = update.offset(), end = begin + update.data().length;
                    var ranges = written.computeIfAbsent(update.buffer().bits(), ignored -> new java.util.TreeMap<>());
                    var floor = ranges.floorEntry(begin);
                    var ceiling = ranges.ceilingEntry(begin);
                    if ((floor != null && floor.getValue() > begin)
                            || (ceiling != null && ceiling.getKey() < end)) {
                        // Preserve list-order semantics for overlapping updates (e.g. animated materials).
                        barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                0, barrier, null, null);
                        written.clear();
                        ranges = new java.util.TreeMap<>(); written.put(update.buffer().bits(), ranges);
                    }
                    ranges.put(begin, end);
                    if (staged) uploadLease.copy(cmd, update.buffer().bits(), begin, update.data(), uploadRegion);
                    else {
                        upload.clear().put(update.data()).flip();
                        vkCmdUpdateBuffer(cmd, update.buffer().bits(), begin, upload);
                    }
                }
                barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        0, barrier, null, null);
            } finally { if (upload != null) MemoryUtil.memFree(upload); }
            diagnosticsNotes.add("buffer-uploads=" + updates.size());
            return this;
        }

        @Override
        public CommandBuffer copyBuffer(GpuDevice.Handle source, long sourceOffset,
                                        GpuDevice.Handle destination, long destinationOffset, long bytes) {
            if (ended || inRenderPass) throw new IllegalStateException("Buffer copy outside recording");
            if (source == null || destination == null || source.isNull() || destination.isNull()
                    || source.kind() != GpuDevice.Handle.Kind.VK_BUFFER
                    || destination.kind() != GpuDevice.Handle.Kind.VK_BUFFER
                    || source.bits() == destination.bits() || sourceOffset < 0 || destinationOffset < 0
                    || bytes <= 0 || ((sourceOffset | destinationOffset | bytes) & 3) != 0
                    || bytes > source.size() || sourceOffset > source.size() - bytes
                    || bytes > destination.size() || destinationOffset > destination.size() - bytes)
                throw new IllegalArgumentException("Invalid buffer copy range");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack);
                barrier.get(0).sType$Default()
                        .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, barrier, null, null);
                var region = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack);
                region.get(0).srcOffset(sourceOffset).dstOffset(destinationOffset).size(bytes);
                vkCmdCopyBuffer(cmd, source.bits(), destination.bits(), region);
                barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        0, barrier, null, null);
            }
            return this;
        }

        @Override
        public CommandBuffer writeAccelStruct(String name, dev.luxloader.api.gpu.AcceleratorHandle accel) {
            if (accel != null && accel.supported()) {
                boundAccel.put(name, accel.nativeHandle());
                graphicsDescriptorsBound = false;
            }
            return this;
        }

        @Override
        public CommandBuffer pushConstants(byte[] data) {
            pendingPushConstants = data;
            return this;
        }

        @Override
        public CommandBuffer dispatch(int groupsX, int groupsY, int groupsZ) {
            if (inRenderPass || boundPipeline == 0L
                    || boundBindPoint != VK_PIPELINE_BIND_POINT_COMPUTE) {
                throw new dev.luxloader.core.util.LuxException(
                        tr("Bind a compute pipeline before dispatch (command buffer ") + label + "）");
            }
            // Write push constants using the pipeline layout, bind descriptors, then dispatch.
            if (pendingPushConstants != null && pendingPushConstants.length > 0) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer data = MemoryUtil.memAlloc(pendingPushConstants.length);
                    try {
                        data.put(pendingPushConstants).flip();
                        vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, data);
                    } finally {
                        MemoryUtil.memFree(data);
                    }
                }
                pendingPushConstants = null;
            }
            // Validate descriptors whenever the pipeline declares bindings, even if no resources were written; otherwise the entirely-unbound case would bypass checks and dispatch undefined reads.
            if (activePipelineDesc != null && !activePipelineDesc.bindings().isEmpty()) {
                bindDescriptors();
            }
            vkCmdDispatch(cmd, Math.max(1, groupsX), Math.max(1, groupsY), Math.max(1, groupsZ));
            return this;
        }

        @Override
        public CommandBuffer dispatchIndirect(GpuDevice.Handle args, long offset) {
            if (args == null || args.isNull()) {
                throw new IllegalArgumentException(tr("Indirect dispatch argument buffer must not be null"));
            }
            vkCmdDispatchIndirect(cmd, args.bits(), offset);
            return this;
        }

        @Override
        public CommandBuffer computeBufferBarrier() {
            if (inRenderPass) throw new IllegalStateException("Compute buffer barrier inside a raster pass");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var memory = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack);
                memory.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, memory, null, null);
            }
            return this;
        }

        @Override
        public CommandBuffer endRenderPass() {
            if (!inRenderPass) {
                throw new IllegalStateException("No render pass is active");
            }
            var capabilities = device.vkDevice().getCapabilities();
            if (capabilities.vkCmdEndRendering != 0L) {
                vkCmdEndRendering(cmd);
            } else if (capabilities.vkCmdEndRenderingKHR != 0L) {
                vkCmdEndRenderingKHR(cmd);
            } else {
                throw new IllegalStateException(tr("Host Vulkan device exposes no dynamic rendering end entry point")
                        + "（vkCmdEndRendering / vkCmdEndRenderingKHR）");
            }
            inRenderPass = false;
            return this;
        }

        @Override
        public CommandBuffer writeTimestamp() {
            // The query pool belongs to the device, not this command buffer.
            VulkanCommands.this.stampFrameTimestamp(this);
            return this;
        }

        @Override
        public CommandBuffer end() {
            if (ended) {
                return this;
            }
            if (inRenderPass) {
                throw new IllegalStateException("End the render pass before ending its command buffer");
            }
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
            ended = true;
            return this;
        }

        /** Returns the command buffer to its pool. */
        void free() {
            if (cmdPool == 0L) {
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                org.lwjgl.PointerBuffer p = stack.mallocPointer(1);
                p.put(0, cmd.address());
                vkFreeCommandBuffers(device.vkDevice(), cmdPool, p);
            }
            if (uploadLease != null) { uploadLease.close(); uploadLease = null; }
        }

        /** Diagnostics recorded by this command buffer. */
        List<String> notes() {
            return List.copyOf(diagnosticsNotes);
        }

        /** Trace-only identity. A driver-recycled VkCommandBuffer handle cannot establish
         *  buffer identity (two logically different buffers have been observed sharing one
         *  handle), so each recording gets our own tag. */
        private final String ownerTag = "b" + TRACED_BUFFER_SEQ.incrementAndGet();

        /** Trace-only: barriers recorded into this buffer since the last submit. */
        private int tracedBarriers;

        /** Trace-only: images those barriers touched. */
        private final java.util.Set<Long> tracedImages = new java.util.LinkedHashSet<>();

        /**
         * Historical experiment: adding same-layout barriers before real transitions did not reduce remaining
         * first-use VUID 09600 reports, but added thousands of barriers. The experiment was reverted. Do not
         * revive per-command-buffer layout-stating barriers without a reproducible failure and demonstrated
         * benefit.
         */
        private final java.util.Set<Long> layoutStated = new java.util.LinkedHashSet<>();

        private void insertImageBarrier(long image, int oldLayout, int newLayout,
                                        long srcAccess, long dstAccess, ImageViewDesc view) {
            if (TRACE_LAYOUTS) {
                tracedBarriers++;
                tracedImages.add(image);
                System.out.println("[VK] barrier owner=" + ownerTag
                        + " image=0x" + Long.toHexString(image)
                        + " " + layoutName(oldLayout) + " -> " + layoutName(newLayout)
                        + " srcAccess=0x" + Long.toHexString(srcAccess)
                        + " dstAccess=0x" + Long.toHexString(dstAccess));
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
                barrier.get(0)
                        .sType$Default()
                        .oldLayout(oldLayout)
                        .newLayout(newLayout)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image)
                        .srcAccessMask((int) srcAccess)
                        .dstAccessMask((int) dstAccess);
                barrier.get(0).subresourceRange()
                        .aspectMask(imageAspect(image, view, newLayout))
                        .baseMipLevel(view == null ? 0 : view.baseMip())
                        .levelCount(VK_REMAINING_MIP_LEVELS)
                        .baseArrayLayer(view == null ? 0 : view.baseLayer())
                        .layerCount(VK_REMAINING_ARRAY_LAYERS);
                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        0, null, null, barrier);
            }
        }

        private int imageAspect(long image, ImageViewDesc view, int layout) {
            Integer format = externalFormats.get(image);
            if (format != null && dev.luxloader.api.gpu.GpuFormat.fromVk(format).isDepth()) {
                return VK_IMAGE_ASPECT_DEPTH_BIT;
            }
            if (view != null && view.format() != null && view.format().isDepth()) {
                return VK_IMAGE_ASPECT_DEPTH_BIT;
            }
            return aspectOf(layout);
        }

        private void bindDescriptors() {
            if (activePipelineDesc == null) {
                diagnosticsNotes.add(tr("Skipping descriptor binding: pipeline description missing"));
                return;
            }
            bindDescriptors(activePipelineDesc.bindings(), activePipelineDesc.name(),
                    VK_PIPELINE_BIND_POINT_COMPUTE, VK_SHADER_STAGE_COMPUTE_BIT);
        }

        private void bindDescriptors(List<ComputePipelineDesc.Binding> bindings, String key,
                                     int bindPoint, int stages) {
            long layout = ensureDescriptorSetLayout(key, bindings, stages);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long descriptorSet = allocateDescriptorSet(key, bindings, layout, stack);

                List<VkWriteDescriptorSet> writes = new ArrayList<>();
                List<VkDescriptorImageInfo> imageInfos = new ArrayList<>();
                List<VkDescriptorBufferInfo> bufferInfos = new ArrayList<>();

                // Declared but unwritten binding numbers and reasons; nonempty means an incomplete descriptor set.
                java.util.Map<Integer, String> unwritten = new java.util.LinkedHashMap<>();

                for (ComputePipelineDesc.Binding binding : bindings) {
                    String name = binding.name();

                    // Reject descriptor arrays unsupported by the name-based single-resource API. Writing only element zero leaves later declared elements undefined even though the binding appears populated.
                    if (binding.count() > 1) {
                        var images = boundImageArrays.get(name);
                        if (binding.type() != ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE_ARRAY
                                || images == null || images.size() != binding.count()) {
                            unwritten.put(binding.binding(), name + " requires " + binding.count() + " sampled images");
                            continue;
                        }
                        var infos = VkDescriptorImageInfo.calloc(images.size(), stack);
                        boolean complete = true;
                        for (int i = 0; i < images.size(); i++) {
                            long image = images.get(i).bits();
                            long view = viewResolver == null ? 0 : viewResolver.apply(image);
                            if (view == 0) { complete = false; break; }
                            infos.get(i).imageView(view).imageLayout(currentLayout(image));
                        }
                        if (!complete) {
                            unwritten.put(binding.binding(), name + " has an unavailable image view");
                            continue;
                        }
                        writes.add(VkWriteDescriptorSet.calloc(stack).sType$Default()
                                .dstSet(descriptorSet).dstBinding(binding.binding()).dstArrayElement(0)
                                .descriptorType(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                                .descriptorCount(images.size()).pImageInfo(infos));
                        continue;
                    }

                    VkWriteDescriptorSet write = VkWriteDescriptorSet.calloc(stack)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(binding.binding())
                            .dstArrayElement(0)
                            .descriptorCount(1)
                            .descriptorType(descriptorTypeOf(binding.type()));

                    switch (binding.type()) {
                        case COMBINED_IMAGE_SAMPLER, SAMPLED_IMAGE, STORAGE_IMAGE, SAMPLED_IMAGE_ARRAY -> {
                            Long imageBits = boundImages.get(name);
                            if (imageBits == null) {
                                diagnosticsNotes.add(tr("Descriptor ") + name + tr(" has no bound texture; skipped"));
                                unwritten.put(binding.binding(), name + tr(" (no texture bound this frame)"));
                                continue;
                            }
                            // The declared layout is a PROMISE about access time:
                            // "the layout that the image subresources accessible from imageView
                            // will be in at the time this descriptor is accessed".
                            // So it may only state what we actually establish in THIS command
                            // buffer. It used to be hardcoded GENERAL, which was wrong twice
                            // over: sampled bindings are parked in SHADER_READ_ONLY_OPTIMAL by
                            // writeImage, and host-owned images get no barrier from us at all.
                            // A declaration nothing backs up is exactly what the validation
                            // layer reports as VUID-vkCmdDraw-None-09600
                            // ("expects VK_IMAGE_LAYOUT_GENERAL--instead, current layout is
                            // VK_IMAGE_LAYOUT_UNDEFINED"), reproducibly, offline.
                            int declaredLayout;
                            if (externalImages.contains(imageBits)) {
                                // Host-owned image. We must not move it: the host hardcodes
                                // VK_IMAGE_LAYOUT_GENERAL for every game image and never
                                // transitions (its encoder contains zero layout changes), so
                                // GENERAL is a fact of its contract, not our choice. But a fact
                                // nobody stated is still unstated -- emit a layout-preserving
                                // barrier (GENERAL -> GENERAL) so the declaration below is backed
                                // by something in this command buffer. This changes nothing about
                                // the image; it only stops the layout from being read as
                                // UNDEFINED.
                                if (!inRenderPass) {
                                    insertImageBarrier(imageBits, VK_IMAGE_LAYOUT_GENERAL,
                                            VK_IMAGE_LAYOUT_GENERAL,
                                            accessMaskOf(Access.SHADER_WRITE),
                                            accessMaskOf(Access.SHADER_READ),
                                            ImageViewDesc.full());
                                }
                                declaredLayout = VK_IMAGE_LAYOUT_GENERAL;
                            } else if (binding.type()
                                    == ComputePipelineDesc.DescriptorType.STORAGE_IMAGE) {
                                // Storage images are written by the shader, so they must be in
                                // GENERAL. writeImage() cannot know the binding type -- it is
                                // resolved here, by name -- and it unconditionally uses
                                // Access.SHADER_READ, which parks the image in
                                // SHADER_READ_ONLY_OPTIMAL. Move it here, before vkCmdDispatch.
                                transition(ImageHandle.vkImage(imageBits, name),
                                        ImageViewDesc.full(), Access.SHADER_READ,
                                        Access.SHADER_WRITE);
                                declaredLayout = VK_IMAGE_LAYOUT_GENERAL;
                            } else {
                                // Sampled bindings stay where writeImage put them. Declare that,
                                // not GENERAL.
                                declaredLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                            }
                            long viewHandle = viewResolver == null ? 0L
                                    : viewResolver.apply(imageBits);
                            if (viewHandle == 0L) {
                                diagnosticsNotes.add(tr("Descriptor ") + name + tr(" has no usable texture view; skipped"));
                                unwritten.put(binding.binding(), name + tr(" (texture view unavailable)"));
                                continue;
                            }
                            // Combined samplers need both sampler and imageView; separate sampled/storage images need only imageView.
                            long samplerHandle = 0L;
                            if (binding.type() == ComputePipelineDesc.DescriptorType.COMBINED_IMAGE_SAMPLER) {
                                samplerHandle = ensureSampler(false);
                            }
                            VkDescriptorImageInfo info = VkDescriptorImageInfo.calloc(stack)
                                    .sampler(samplerHandle)
                                    .imageView(viewHandle)
                                    .imageLayout(declaredLayout);
                            imageInfos.add(info);
                            write.pImageInfo(VkDescriptorImageInfo.create(info.address(), 1));
                            writes.add(write);
                        }
                        case UNIFORM_BUFFER, STORAGE_BUFFER -> {
                            long[] range = boundBuffers.get(name);
                            if (range == null) {
                                diagnosticsNotes.add(tr("Descriptor ") + name + tr(" has no bound buffer; skipped"));
                                unwritten.put(binding.binding(), name + tr(" (no buffer bound this frame)"));
                                continue;
                            }
                            VkDescriptorBufferInfo info = VkDescriptorBufferInfo.calloc(stack)
                                    .buffer(range[0])
                                    .offset(range[1])
                                    .range(range[2] > 0 ? range[2] : VK_WHOLE_SIZE);
                            bufferInfos.add(info);
                            write.pBufferInfo(VkDescriptorBufferInfo.create(info.address(), 1));
                            writes.add(write);
                        }
                        case ACCELERATION_STRUCTURE -> {
                            // Acceleration-structure descriptors use VkWriteDescriptorSetAccelerationStructureKHR on pNext, not buffer/image info. Keep the structure alive on the same MemoryStack through vkUpdateDescriptorSets.
                            Long accel = boundAccel.get(name);
                            if (accel == null || accel == 0L) {
                                diagnosticsNotes.add(tr("Descriptor ") + name + tr(" has no bound acceleration structure; skipped"));
                                unwritten.put(binding.binding(), name + tr(" (no acceleration structure bound this frame)"));
                                continue;
                            }
                            org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR asWrite =
                                    org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR
                                            .calloc(stack)
                                            .sType$Default()
                                            .accelerationStructureCount(1)
                                            .pAccelerationStructures(stack.mallocLong(1).put(0, accel));
                            write.pNext(asWrite.address());
                            writes.add(write);
                        }
                    }
                }

                // Every declared descriptor must be written before dispatch. Report missing binding names/reasons and reject execution, rather than logging and allowing VUID 08114 undefined reads. Optional inputs require separate layouts or valid placeholder resources.
                if (!unwritten.isEmpty()) {
                    StringBuilder message = new StringBuilder(256);
                    message.append(tr("Not all declared descriptor bindings were written; refusing dispatch"))
                            .append(tr(" (reading unwritten descriptors is undefined behavior).\n"))
                            .append(tr("  Pipeline: ")).append(key).append('\n');
                    unwritten.forEach((slot, reason) -> message.append("  binding ")
                            .append(slot).append(" → ").append(reason).append('\n'));
                    message.append(tr("  Rule: bindings() is a fixed declaration; "))
                            .append(tr("no binding is optional. Write every declared binding before dispatch.\n"))
                            .append(tr("  Common cause: the corresponding frame resource is unavailable.\n"))
                            .append(tr("  Fix: omit unavailable resources, split optional inputs into separate pipelines, "))
                            .append(tr("or provide placeholder textures from the pass."));
                    throw new dev.luxloader.core.util.LuxException(message.toString());
                }

                if (!writes.isEmpty()) {
                    VkWriteDescriptorSet.Buffer buffer = VkWriteDescriptorSet.calloc(writes.size(), stack);
                    for (int i = 0; i < writes.size(); i++) {
                        buffer.put(i, writes.get(i));
                    }
                    if (TRACE_LAYOUTS) {
                        System.out.println("[VK] updateDescriptorSets cb=0x"
                                + Long.toHexString(cmd.address())
                                + " set=0x" + Long.toHexString(descriptorSet)
                                + " writes=" + writes.size()
                                + " declaredLayout=GENERAL");
                    }
                    vkUpdateDescriptorSets(device.vkDevice(), buffer, null);
                }

                vkCmdBindDescriptorSets(cmd, bindPoint, pipelineLayout,
                        0, stack.longs(descriptorSet), null);
                diagnosticsNotes.add("descriptors(writes=" + writes.size()
                        + " images=" + boundImages.size() + " buffers=" + boundBuffers.size() + ")");
            }
        }

        private static int aspectOf(int layout) {
            boolean depth = layout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                    || layout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL
                    || layout == VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
            return depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
        }

        private static int layoutOf(Access access) {
            return switch (access) {
                case NONE -> VK_IMAGE_LAYOUT_UNDEFINED;
                case SHADER_READ -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                case SHADER_WRITE -> VK_IMAGE_LAYOUT_GENERAL;
                case COLOR_ATTACHMENT -> VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                case DEPTH_STENCIL -> VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
                case TRANSFER_READ -> VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                case TRANSFER_WRITE -> VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
                case PRESENT -> VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            };
        }

        private static long accessMaskOf(Access access) {
            return switch (access) {
                case NONE -> 0L;
                case SHADER_READ -> VK_ACCESS_SHADER_READ_BIT;
                case SHADER_WRITE -> VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                case COLOR_ATTACHMENT -> VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                case DEPTH_STENCIL -> VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                case TRANSFER_READ -> VK_ACCESS_TRANSFER_READ_BIT;
                case TRANSFER_WRITE -> VK_ACCESS_TRANSFER_WRITE_BIT;
                case PRESENT -> 0L;
            };
        }
    }
}
