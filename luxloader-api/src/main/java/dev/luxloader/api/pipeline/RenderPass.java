package dev.luxloader.api.pipeline;

import java.util.Objects;

/**
 * A rendering pass is the smallest independently switchable unit. It declares resource access for
 * ordering/barriers, records work in encode and owns resources released when removed. See queue() for
 * queue selection and host-resource restrictions. All methods run on the render thread; avoid long
 * blocking work and load shaders during initialize.
 */
public interface RenderPass {

    /** Pass identifier, usually derived from pipelineId.child("pass-name"). */
    dev.luxloader.api.GpuId id();

    /** Display name. */
    String name();

    /** Semantic category for diagnostics and conflict reporting; does not determine execution order. */
    StageKind kind();

    /**
     * Declares input resource names. Host resources use
     * frame.color/depth/motion/exposure/ui/swapchain/history; plugin resources use their own namespace,
     * e.g. my.color or my.temp. Accurate reads place a pass after its producers and avoid unnecessary
     * barriers.
     */
    default java.util.Set<String> inputs() {
        return java.util.Set.of();
    }

    /** Names of resources written by this pass. */
    default java.util.Set<String> outputs() {
        return java.util.Set.of();
    }

    /**
     * Requested queue. Submission batches use the declared families with inter-batch semaphores and a
     * final wait. Host resources are graphics-family EXCLUSIVE; passes accessing them are forced back to
     * graphics with a diagnostic. To use compute, first copy the host image on graphics into a
     * loader-owned concurrently shared image. <p>A controlled test on 2026-09-23 recorded
     * queueFamilies=0,1 and queueGroups=2 with compute, versus 0 and 1 without. Validation code 09600
     * appeared 10 versus 4 times, all during startup, with over 1500 submissions per arm. An earlier claim
     * of 1858 recurring reports was not reproducible and compared different code revisions; it must not
     * guide queue policy.
     * @return null to use the pipeline default queue
     */
    default dev.luxloader.api.gpu.GpuQueue queue() {
        return null;
    }

    /** Resource provider scoped to the pass lifetime. */
    GpuResourceProvider resources();

    /**
     * Initializes shaders and pipeline objects, returning resource requirements (possibly
     * ResourceRequest.EMPTY). An exception skips the pass, or the entire pipeline if the pass is required.
     */
    ResourceRequest initialize(RenderContext ctx);

    /**
     * Records this frame after graph compilation. RenderPipeline.encodeFrame first adds passes, the loader
     * compiles their order, then invokes each encode method. Do not add graph passes here: barriers and
     * execution order have already been determined.
     */
    void encode(FrameGraph graph, dev.luxloader.api.frame.FrameContext frame);

    /**
     * Handles size/format changes. Return true if adapted successfully; false asks the loader to close and
     * recreate the pass.
     */
    default boolean resize(FrameSetup newSetup) {
        return false;
    }

    /** Whether required; optional failures produce warnings and allow remaining passes to continue. */
    default boolean required() {
        return true;
    }

    /** Whether enabled; false skips the frame without reconstruction. */
    default boolean enabled() {
        return true;
    }

    /** Release resources when removed. The loader ensures the GPU is idle first. */
    default void close() {
    }

    /** Add an ordering constraint after previous, beyond resource input dependencies. */
    default void dependsOn(RenderPass previous) {
        Objects.requireNonNull(previous, "previous");
    }
}
