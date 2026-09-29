package dev.luxloader.api.plugin;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.config.ConfigSchema;
import dev.luxloader.api.diag.Diagnostics;
import dev.luxloader.api.event.ClientEventService;
import dev.luxloader.api.nativebridge.NativeBridge;
import dev.luxloader.api.pipeline.PipelineDescriptor;
import dev.luxloader.api.pipeline.PipelineSettings;
import dev.luxloader.api.pipeline.RenderPipeline;
import dev.luxloader.api.state.ClientStateService;

import java.util.List;
import java.util.function.Supplier;

/**
 * Host services supplied through PluginBootstrap.host(). Registration and runtime services share one
 * object so plugins retain a single reference.
 */
public interface HostServices {
    /**
     * Register a mod's geometry source. Registration lasts until the plugin unloads or calls
     * {@link #deregisterSceneContributor(GpuId)}; it is not removed by pipeline deactivation.
     * The ID must equal this plugin's ID or be a child of it, so ownership stays attributable.
     * @param id contribution ID inside this plugin's namespace
     * @param contributor invoked on the render thread before the frame plan
     * @throws IllegalArgumentException when the ID is outside the plugin's namespace or already registered
     */
    default void registerSceneContributor(GpuId id, dev.luxloader.api.scene.SceneContributor contributor) {
        String pluginId = mod().id().toString();
        if (id == null || contributor == null) {
            throw new IllegalArgumentException("Scene contributor registration from " + pluginId
                    + " requires both an ID and a contributor");
        }
        if (!dev.luxloader.api.scene.SceneOwnership.isOwnedBy(pluginId, id)) {
            throw new IllegalArgumentException("Scene contributor " + id
                    + " must be declared inside plugin " + pluginId + "'s own namespace");
        }
        throw new UnsupportedOperationException("Host does not expose scene contributors");
    }

    /**
     * Release a geometry source registered by this plugin. Use it when a plugin decides at runtime to
     * stop contributing; the loader also releases everything the plugin registered when it unloads.
     * Unknown IDs are ignored, and releasing another plugin's registration is rejected.
     * @param id contribution ID previously passed to {@link #registerSceneContributor}
     * @throws IllegalArgumentException when the ID belongs to a different plugin
     */
    default void deregisterSceneContributor(GpuId id) {
        throw new UnsupportedOperationException("Host does not expose scene contributors");
    }

    default dev.luxloader.api.scene.ResourceAccess resources() {
        return dev.luxloader.api.scene.ResourceAccess.EMPTY;
    }

    /** Environment and local-player observations owned by this plugin instance. */
    default ClientStateService clientState() {
        return ClientStateService.EMPTY;
    }

    /** Client behavior observations and plugin-defined events, delivered at the client safe update point. */
    default ClientEventService clientEvents() {
        return ClientEventService.EMPTY;
    }

    /** Plugin metadata. */
    LuxMod mod();

    /**
     * Registers a rendering pipeline. Its factory runs only on activation, so registration itself
     * allocates no GPU resources.
     * @param id pipeline ID
     * @param descriptor requirements, frame ownership and priority
     * @param factory pipeline factory
     */
    void registerPipeline(GpuId id, PipelineDescriptor descriptor, Supplier<RenderPipeline> factory);

    /**
     * Register capability availability for other pipelines, including device features or plugin-provided
     * middleware.
     */
    void registerCapability(CapabilityDescriptor descriptor);

    /** Register boolean availability. */
    default void registerCapability(String id, boolean available, String detail) {
        registerCapability(new CapabilityDescriptor(id, CapabilityLevel.of(available), id,
                mod().id().toString(), detail, java.util.Map.of(), System.nanoTime()));
    }

    /** Register a support level. */
    default void registerCapability(String id, CapabilityLevel level, String detail) {
        registerCapability(new CapabilityDescriptor(id, level, id, mod().id().toString(),
                detail, java.util.Map.of(), System.nanoTime()));
    }

    /** Record a nonfatal warning for logs and diagnostic reports, such as known driver limitations. */
    void warn(String message);

    /**
     * Registers a native library directory within the plugin JAR. The loader extracts it and adds it to
     * the native search path.
     * @param relativePath JAR-relative path, e.g. natives/windows-x64
     */
    void registerNativeLibraryPath(String relativePath);

    /** Native library bridge (FFM/JNI); unsupported runtimes supply an implementation that reports unavailability. */
    NativeBridge nativeBridge();

    /**
     * The plugin's declared configuration schema, cached from PipelinePlugin.declareConfig during loading.
     * The plugin remains the single source of truth; this method returns the same declaration at runtime.
     */
    ConfigSchema declareConfig();

    /** Read this plugin's configuration. */
    PipelineSettings settings();

    /** Diagnostic output. */
    Diagnostics diagnostics();

    /**
     * Read-only capability registry for runtime fallback decisions, such as ray tracing, native/fallback
     * XeSS and dedicated compute queues. Query it instead of probing native APIs again.
     */
    dev.luxloader.api.capability.CapabilityRegistry capabilities();

    /** Read-only registered pipeline IDs for plugin cooperation and compatibility checks. */
    List<GpuId> registeredPipelineIds();

    /** Loader version for compatibility checks. */
    String loaderVersion();

    /** Current game version, e.g. 26.3; empty when unknown. */
    String gameVersion();

    /** Request plugin/configuration reload after this frame, as used by the UI reload action or backend switches. */
    void requestReload(String reason);

    /** Request a pipeline switch at the next frame boundary. */
    void requestPipelineSwitch(GpuId pipelineId, String reason);

    /** Active pipeline ID, or empty if none is active. */
    java.util.Optional<GpuId> activePipeline();
}
