package dev.luxloader.api.plugin;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.pipeline.PipelineDescriptor;
import dev.luxloader.api.pipeline.RenderPipeline;

import java.util.List;
import java.util.function.Supplier;

/**
 * Plugin entry point. Lifecycle: onLoad registers pipelines, capabilities and configuration without
 * GPU work; optional probe runs after device readiness; activated pipelines initialize, process frames
 * and close; onUnload releases plugin resources. Registration is separate from probing so UI can
 * discover pipelines before a device exists, while later probes establish actual runtime availability.
 */
public interface PipelinePlugin {

    /** Plugin metadata. */
    LuxMod mod();

    /**
     * Register pipelines and capabilities, returning within milliseconds. Do not allocate GPU resources or
     * perform blocking I/O. Exceptions skip this plugin with diagnostics.
     */
    void onLoad(PluginBootstrap bootstrap) throws Exception;

    /**
     * Probes availability after device readiness. Register capabilities here and report unsupported
     * environments through bootstrap.host().warn. The default does nothing.
     */
    default void probe(PluginBootstrap bootstrap) {
    }

    /**
     * Declares the static configuration schema used to generate defaults and UI, including during prescan
     * before loading or device creation. The default schema is empty.
     */
    default dev.luxloader.api.config.ConfigSchema declareConfig() {
        return dev.luxloader.api.config.ConfigSchema.builder().build();
    }

    /**
     * Declared dependencies on other plugin IDs; the loader orders onLoad calls accordingly and reports
     * missing dependencies.
     */
    default List<GpuId> dependencies() {
        return List.of();
    }

    /** Release plugin-level resources on unload; pipeline-level resources belong to each pipeline. */
    default void onUnload() {
    }

    /** Convenience interface for plugins providing one pipeline without custom registration code. */
    interface SinglePipeline extends PipelinePlugin {

        /** Pipeline identifier. */
        GpuId pipelineId();

        /** Pipeline description. */
        PipelineDescriptor pipelineDescriptor();

        /** Pipeline factory. */
        Supplier<RenderPipeline> pipelineFactory();

        @Override
        default void onLoad(PluginBootstrap bootstrap) {
            bootstrap.host().registerPipeline(pipelineId(), pipelineDescriptor(), pipelineFactory());
        }
    }
}
