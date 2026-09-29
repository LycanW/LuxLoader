package dev.luxloader.api.raytracing;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.gpu.AcceleratorHandle;
import dev.luxloader.api.gpu.ComputePipelineDesc;
import dev.luxloader.api.gpu.GpuDevice;

/**
 * Ray tracing backend for acceleration-structure build/update and either inline ray queries or a ray
 * tracing pipeline. Ray queries integrate into compute/fragment shaders without per-material SBT
 * management and suit large, frequently updated Minecraft scenes. Backends report actual ray tracing
 * pipeline support separately.
 */
public interface RayTracingBackend extends AutoCloseable {

    /** Technology ID under Technologies.RAY_TRACING, e.g. ray_query. */
    String technology();

    String displayName();

    /**
     * Probes ray tracing availability. Unsupported hardware returns UNSUPPORTED so the pipeline can select
     * a screen-space fallback.
     */
    CapabilityLevel probe(GpuDevice device);

    /** Whether VK_KHR_ray_tracing_pipeline is available. */
    boolean supportsRayTracingPipeline();

    /** Whether VK_KHR_ray_query is available. */
    boolean supportsRayQuery();

    /** Maximum supported ray recursion depth. */
    int maxRecursionDepth();

    /**
     * Builds/updates acceleration structures. Handles remain valid until the next frame; implementations
     * should reuse storage to avoid per-frame BVH allocation.
     * @return TLAS handle, or AcceleratorHandle.unsupported when unavailable
     */
    AcceleratorHandle buildScene(SceneBuilder scene);

    /**
     * Dispatches a ray tracing pass after binding its pipeline and output textures at the requested
     * dimensions. Records through pass.commands(); the host submits the commands.
     */
    void trace(RayTracingScene scene, TracePass pass);

    /**
     * Ray tracing dispatch.
     * @param pipeline ray tracing/compute pipeline
     * @param outputs output images by semantic name, e.g. diffuse/specular/shadow
     * @param inputs input images, e.g. depth/normal/gbuffer
     * @param dispatchWidth dispatch width, usually output width
     * @param dispatchHeight dispatch height
     * @param pushConstants push constant bytes
     * @param rayBudget per-frame ray budget, ignored when unsupported
     * @param commands command recorder
     */
    record TracePass(
            ComputePipelineDesc pipeline,
            java.util.Map<String, dev.luxloader.api.gpu.ImageHandle> outputs,
            java.util.Map<String, dev.luxloader.api.gpu.ImageHandle> inputs,
            int dispatchWidth,
            int dispatchHeight,
            byte[] pushConstants,
            long rayBudget,
            dev.luxloader.api.gpu.GpuCommands.CommandBuffer commands) {

        public TracePass {
            if (dispatchWidth <= 0 || dispatchHeight <= 0) {
                throw new IllegalArgumentException(tr("Dispatch dimensions must be positive"));
            }
            outputs = outputs == null ? java.util.Map.of() : java.util.Map.copyOf(outputs);
            inputs = inputs == null ? java.util.Map.of() : java.util.Map.copyOf(inputs);
            pushConstants = pushConstants == null ? new byte[0] : pushConstants.clone();
        }
    }

    /** Scene build request including the incremental change set in addition to the full scene. */
    interface SceneBuilder {

        /** Full scene for this frame. */
        RayTracingScene scene();

        /** Names of meshes whose changed geometry requires rebuilding. */
        java.util.Set<String> dirtyMeshes();

        /** Names of meshes to remove. */
        java.util.Set<String> removedMeshes();

        /** Whether structural instance changes require a full TLAS rebuild. */
        boolean rebuildTopLevel();

        /** Reports build time in nanoseconds for diagnostics. */
        void reportBuildTime(long nanos);
    }

    /** Releases acceleration structures and pipelines. The host guarantees GPU idle first. */
    @Override
    void close();
}
