package dev.luxloader.api.plugin;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.capability.CapabilityRegistry;
import dev.luxloader.api.config.ConfigStore;
import dev.luxloader.api.config.ConfigSchema;
import dev.luxloader.api.config.ConfigView;
import dev.luxloader.api.diag.Diagnostics;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.ImageHandle;
import dev.luxloader.api.host.HostAdapter;
import dev.luxloader.api.pipeline.PipelineDescriptor;
import dev.luxloader.api.pipeline.Requirements;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;

/**
 * Host entry point for driving the kernel. Prefer HostAdapter with HostSession, which forwards host
 * facts and kernel decisions. Hosts needing manual timing can call
 * onFrameBegin/onBeforePresent/onFrameEnd directly. <p>Initialization discovers plugins, reads
 * configuration and creates the registry without requiring a device. attachDevice enables activation
 * once the host device exists. Menus, other backends and device creation failures are valid standby
 * states: discovery, configuration and diagnostics remain available while pipelines stay inactive.
 */
public interface RenderDriver extends AutoCloseable {

    /** Loader version. */
    String version();

    /**
     * Initializes plugin discovery, configuration and the capability registry. Repeated calls do nothing.
     * Device readiness is not required.
     */
    void initialize(DeviceRequest request);

    // State queries.

    /**
     * Whether plugin discovery and configuration initialization are complete. This may be true without a
     * device; use isDeviceReady() to determine whether activation is possible.
     */
    boolean isInitialized();

    /** Whether the graphics device is ready. */
    boolean isDeviceReady();

    /** Active pipeline ID. */
    Optional<GpuId> activePipelineId();

    /** Saved UI selection, available even before device readiness. */
    default Optional<GpuId> configuredPipelineId() {
        return activePipelineId();
    }

    /** Registered pipeline snapshots. */
    List<PipelineInfo> pipelines();

    /** Runtime failure retained for both the host notification and the settings UI. */
    record PipelineFailure(GpuId pipeline, String reason, String detail) { }

    /** Consume each runtime failure once; hosts should surface it to the player. */
    default Optional<PipelineFailure> pollPipelineFailure() { return Optional.empty(); }

    /** Loaded plugin IDs. */
    List<String> loadedPlugins();

    /** Number of registered pipelines. */
    int registeredPipelineCount();

    // Device attachment.

    /**
     * Attaches the device created by the host, enabling pipeline activation. The loader must never destroy
     * a device with ownedByHost=true. Only the first attachment is accepted; later calls return false with
     * a warning. Zero handles mean unavailable and return false without throwing.
     * @return whether attachment succeeded
     */
    boolean attachDevice(HostAdapter.HostDevice hostDevice);

    // Pipeline management.

    /**
     * Activates a pipeline. Drain in-flight frames and wait for GPU idle before closing the old pipeline
     * and initializing the replacement.
     * @param id pipeline ID
     * @param reason diagnostic reason
     * @return success; failure retains the previous pipeline
     */
    boolean activatePipeline(GpuId id, String reason);

    /** Deactivates the pipeline and restores default game rendering. */
    void deactivatePipeline(String reason);

    /**
     * Rescans plugins, rereads configuration and rebuilds the active pipeline. Used by the Apply action
     * and backend switching.
     */
    void reload(String reason);

    /** Requests reload at the next frame boundary; safe to call from plugin callbacks. */
    void requestReload(String reason);

    /** Requests a pipeline switch at the next frame boundary; safe for plugin callbacks. */
    void requestPipelineSwitch(GpuId id, String reason);

    /** Processes pending reload/switch requests at a frame boundary. Returns whether an action ran. */
    default boolean processPendingActions() {
        return false;
    }

    /** Validates a pipeline descriptor without instantiation, allowing UI to explain disabled choices. */
    Requirements.Verdict verify(PipelineDescriptor descriptor);

    // UI controls.

    /** Whether the user enabled a pipeline. Defaults to true for hosts without disabling support. */
    default boolean isPipelineEnabled(GpuId id) {
        return true;
    }

    /**
     * Persists pipeline enablement. Disabled pipelines are excluded from automatic selection but can still
     * be explicitly activated.
     * @return whether applied; the default false means unsupported
     */
    default boolean setPipelineEnabled(GpuId id, boolean enabled) {
        return false;
    }

    /** Plugin directory for the in-game Open Folder action. */
    default Optional<Path> pipelineDirectory() {
        return Optional.empty();
    }

    /** Configuration declaration and values for the plugin providing a pipeline. */
    default Optional<ConfigSchema> pipelineOptionsSchema(GpuId id) {
        return Optional.empty();
    }

    default Optional<ConfigView> pipelineOptions(GpuId id) {
        return Optional.empty();
    }

    /** Saves selection and settings, applying them at a safe frame boundary. A null ID disables plugin rendering. */
    default boolean applyPipelineChoice(GpuId id, Map<String, Object> options) {
        return false;
    }

    // Host adaptation.

    /**
     * Installs the adapter that supplies host devices, frame textures and frame hooks. The adapter owns
     * knowledge of game internals. Defaults to false for unsupported hosts; independently created devices
     * may use attachDevice alone.
     * @return false if installation failed or another adapter is already installed
     */
    default boolean attachHostAdapter(HostAdapter adapter) {
        return false;
    }

    // Frame driving.

    /**
     * Called once before rendering at each frame boundary to handle pending requests and prepare the
     * frame. Deferring switches prevents destruction during command recording. Exclusive-frame pipelines
     * receive the host's extracted scene snapshot here.
     */
    void onFrameBegin();

    /**
     * Called after composition and before swapchain presentation. Invokes pipeline presentation for
     * upscaling, frame generation and other final effects.
     * @param textures available host textures, possibly incomplete
     * @return presentation decision the host should follow
     */
    PresentOutcome onBeforePresent(HostAdapter.HostFrameTextures textures);

    /** Called after presentation to reclaim frame resources. */
    void onFrameEnd();

    /** Notifies swapchain size changes so the loader can rebuild sized resources or let the pipeline adapt. */
    void onResize(int displayWidth, int displayHeight);

    // Services.

    /** Configuration management. */
    ConfigStore config();

    /** Read-only capability registry. */
    CapabilityRegistry capabilities();

    /** Diagnostics. */
    Diagnostics diagnostics();

    /** Deactivates pipelines, releases devices and unloads plugins. Idempotent. */
    @Override
    void close();

    /**
     * Device initialization request.
     * @param applicationName application name supplied to the driver, potentially selecting driver
     * profiles
     * @param preferDiscreteGpu prefer a discrete GPU on multi-GPU systems
     * @param minVulkanVersion minimum Vulkan version, e.g. 1.3; empty means unrestricted
     * @param requireAsyncCompute prefer a dedicated compute queue, falling back if unavailable
     * @param useExistingDevice attach an externally created device, true for the game host
     * @param headless windowless mode for automated tests or offline rendering
     */
    record DeviceRequest(
            String applicationName,
            boolean preferDiscreteGpu,
            String minVulkanVersion,
            boolean requireAsyncCompute,
            boolean useExistingDevice,
            boolean headless) {

        public static DeviceRequest defaults() {
            return new DeviceRequest("LuxLoader", true, "1.3", false, false, false);
        }

        /** Game host: attach its existing device. */
        public static DeviceRequest attachedToGame() {
            return new DeviceRequest("LuxLoader (in-game)", true, "1.2", false, true, false);
        }

        public DeviceRequest withHeadless(boolean value) {
            return new DeviceRequest(applicationName, preferDiscreteGpu, minVulkanVersion,
                    requireAsyncCompute, useExistingDevice, value);
        }
    }

    /** Loader presentation decision, including generated frames. The host should follow this result. */
    record PresentOutcome(
            boolean useCustomImage,
            ImageHandle image,
            List<ImageHandle> additionalFrames,
            boolean pacing,
            long frameIntervalNs,
            String note) {

        /** Present the game's output unchanged. */
        public static final PresentOutcome PASSTHROUGH =
                new PresentOutcome(false, null, List.of(), false, 0L, "passthrough");

        public PresentOutcome {
            additionalFrames = additionalFrames == null ? List.of() : List.copyOf(additionalFrames);
            note = note == null ? "" : note;
        }

        /** Whether the host should change presentation behavior. */
        public boolean changesPresent() {
            return useCustomImage || !additionalFrames.isEmpty();
        }

        /** Total frames to present: one plus generated frames. */
        public int totalFrames() {
            return changesPresent() ? 1 + additionalFrames.size() : 1;
        }
    }
}
