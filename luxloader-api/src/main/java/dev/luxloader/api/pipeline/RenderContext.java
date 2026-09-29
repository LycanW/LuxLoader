package dev.luxloader.api.pipeline;

import dev.luxloader.api.frame.FrameContext;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.gpu.GpuQueue;
import dev.luxloader.api.plugin.HostServices;

import java.util.Objects;

/**
 * Pipeline environment supplied once at initialize and suitable for retaining in a field. Per-frame
 * resources live in FrameContext.
 * @param services configuration, capabilities, diagnostics, logging and native services
 * @param device GPU device, host-owned or loader-owned
 * @param resources pipeline-scoped resources
 * @param graphicsQueue graphics queue
 * @param computeQueue compute queue, or graphics when no separate queue exists
 * @param transferQueue transfer queue, or graphics when no separate queue exists
 * @param setup initial dimensions/formats, which may change later
 */
public record RenderContext(
        HostServices services,
        GpuDevice device,
        GpuResourceProvider resources,
        GpuQueue graphicsQueue,
        GpuQueue computeQueue,
        GpuQueue transferQueue,
        FrameSetup setup) {

    public RenderContext {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(graphicsQueue, "graphicsQueue");
        computeQueue = computeQueue == null ? graphicsQueue : computeQueue;
        transferQueue = transferQueue == null ? graphicsQueue : transferQueue;
        setup = setup == null ? FrameSetup.forTest(1920, 1080, 0L) : setup;
    }

    /** Current display dimensions. */
    public int displayWidth() {
        return setup.displayWidth();
    }

    public int displayHeight() {
        return setup.displayHeight();
    }

    /** Current render dimensions. */
    public int renderWidth() {
        return setup.renderWidth();
    }

    public int renderHeight() {
        return setup.renderHeight();
    }

    /** Whether an independent asynchronous compute queue is available. */
    public boolean hasAsyncCompute() {
        return computeQueue.dedicated();
    }

    /** Whether a dedicated transfer queue is available. */
    public boolean hasDedicatedTransfer() {
        return transferQueue.dedicated();
    }

    /** Copy the context with new frame settings after a size change; retain resources and queues. */
    public RenderContext withSetup(FrameSetup newSetup) {
        return new RenderContext(services, device, resources, graphicsQueue, computeQueue, transferQueue, newSetup);
    }
}
