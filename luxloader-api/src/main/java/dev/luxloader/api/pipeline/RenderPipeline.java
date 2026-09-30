package dev.luxloader.api.pipeline;

import dev.luxloader.api.frame.FrameContext;
import dev.luxloader.api.gpu.GpuCommands;
import dev.luxloader.api.scene.SceneGeometryFeed;
import dev.luxloader.api.scene.SceneSnapshot;

import java.util.List;

/**
 * Rendering pipeline entry point composed of RenderPass objects. The loader drives lifecycle,
 * submission, synchronization, presentation and error isolation. Resource reads/writes determine
 * execution order.
 * <pre>{@code
 * public final class MyPipeline implements RenderPipeline {
 *     private MyPass pass;
 *     public PipelineDescriptor descriptor() {
 *         return PipelineDescriptor.builder(id, "My pipeline", "1.0.0")
 *                 .kind(PipelineKind.ENHANCEMENT)
 *                 .build();
 *     }
 *     public void initialize(RenderContext ctx) {
 *         pass = new MyPass(descriptor().id().child("bloom"), this);
 *         pass.initialize(ctx);
 *     }
 *     public List<RenderPass> passes() { return List.of(pass); }
 *     public void encodeFrame(FrameGraph graph, FrameContext frame) {
 *         graph.addPass(pass);
 *     }
 *     public PresentRequest present(FrameContext frame, PresentRequest.Target target) {
 *         return new PresentRequest(target, PresentRequest.Request.DEFAULT);
 *     }
 * }
 * }</pre>
 * Lifecycle: initialize, then per-frame setupFrame/encodeFrame/pass encode/present, with resize or
 * settingsChanged as needed, followed by close. No frame methods run before initialize or after close;
 * the GPU is idle when close runs.
 */
public interface RenderPipeline extends AutoCloseable {
    /** Request posed feature geometry for secondary visibility and custom drawing. */
    default boolean requiresDynamicGeometry() { return false; }

    /**
     * Presentation definition IDs this pipeline actually consumes as dynamic scene meshes. Empty by
     * default. Opting in also requires requiresDynamicGeometry(), and the pipeline must draw/use the
     * supplied meshes. Submission/contribution does not assert visible pixels. Contributors use their
     * instance source to keep mesh IDs distinct; GPU retirement still waits for frame completion.
     */
    default java.util.Set<dev.luxloader.api.GpuId> presentationVisualTypes() { return java.util.Set.of(); }

    /**
     * Whether compatibility preparation includes the host's projected entity-shadow
     * decals. A pipeline supplying its own shadows can opt out before these meshes
     * are built, drawn, or offered as secondary geometry. Queried without GPU work.
     */
    default boolean usesPreparedEntityShadows() { return true; }

    /** Pipeline description, consistent with registration. */
    PipelineDescriptor descriptor();

    /**
     * Initialize passes and pipelines. Perform expensive I/O and shader compilation here, not in frame
     * callbacks. Exceptions disable this selection with diagnostics.
     */
    void initialize(RenderContext ctx) throws Exception;

    /** All pipeline passes; the loader caches the list, so do not mutate it within a frame. */
    List<RenderPass> passes();

    /**
     * Selects world passes before Minecraft draws the prepared scene. Null
     * leaves the host frame unchanged. Plugins that own the world return a
     * plan and put their own work at {@link WorldFramePlan.Step#PIPELINE}.
     */
    default WorldFramePlan worldFramePlan() {
        return null;
    }

    /**
     * Choose the world draw mode after the host has prepared this frame's scene
     * metadata. A pipeline may prepare its own scene here before suppressing
     * host terrain; if it cannot render, it should retain PREPARED_SCENE.
     */
    default WorldFramePlan worldFramePlan(SceneSnapshot scene,
                                         SceneGeometryFeed geometry,
                                         GpuCommands commands) {
        return worldFramePlan();
    }

    /**
     * Whether the host executes its prepared world draw into {@code frame.color}.
     * The default preserves terrain, entities, and mod submissions. Pipelines that
     * render the entire world themselves must opt into {@link HostSceneMode#PLUGIN_SCENE}.
     */
    default HostSceneMode hostSceneMode() {
        return HostSceneMode.COMPATIBILITY;
    }

    /**
     * Request the host's prepared geometry and material references even when
     * {@link #hostSceneMode()} retains the host's own scene draw. This allows a
     * hybrid pipeline to trace the accepted chunk meshes while the host still
     * draws entities, block entities and other mod content.
     */
    default boolean requiresSceneSnapshot() {
        return false;
    }

    /**
     * Prepares the frame before encodeFrame, deciding whether to run or change resolution. The default
     * processes the full frame.
     * @return frame controls; null is equivalent to FrameControl.normal()
     */
    default FrameControl setupFrame(FrameSetup setup, FrameContext frame) {
        return FrameControl.normal();
    }

    /**
     * Adds enabled passes to the frame graph. Each pass declares reads/writes, allowing the loader to
     * infer order and barriers. Actual GPU recording happens later in RenderPass.encode.
     */
    void encodeFrame(FrameGraph graph, FrameContext frame);

    /**
     * Decides which image to present, whether to add generated frames and whether to control pacing. The
     * default presents the current swapchain image without changing presentation behavior.
     */
    default PresentRequest present(FrameContext frame, PresentRequest.Target target) {
        return new PresentRequest(target, PresentRequest.Request.DEFAULT);
    }

    /**
     * Handle swapchain dimension or format changes. Return true if adapted; false asks the loader to
     * rebuild all passes.
     */
    default boolean resize(FrameSetup newSetup) {
        return false;
    }

    /**
     * Handles reloaded settings. APPLIED means no further action; NEEDS_REINIT closes and initializes the
     * pipeline without reloading the plugin; NEEDS_PIPELINE_SWAP requests another pipeline.
     */
    default SettingsChangeResult onSettingsChanged(PipelineSettings settings) {
        return SettingsChangeResult.APPLIED;
    }

    /** Result of a settings change. */
    enum SettingsChangeResult {
        /** Applied through a hot update; no rebuild required. */
        APPLIED,
        /** Rebuild this pipeline by initializing it again. */
        NEEDS_REINIT,
        /** Switch to another pipeline. */
        NEEDS_PIPELINE_SWAP
    }

    /** Frame-end callback after command submission for query retirement, statistics and next-frame CPU preparation. */
    default void endFrame(long frameIndex) {
    }

    /** Close all passes and release resources. The loader ensures the GPU is idle first. */
    @Override
    void close();
}
