package dev.luxloader.core.runtime;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.capability.CapabilityRegistry;
import dev.luxloader.api.config.ConfigOption;
import dev.luxloader.api.config.ConfigSchema;
import dev.luxloader.api.config.ConfigStore;
import dev.luxloader.api.config.ConfigView;
import dev.luxloader.api.diag.Diagnostics;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.gpu.GpuQueue;
import dev.luxloader.api.frame.CameraParams;
import dev.luxloader.api.host.HostAdapter;
import dev.luxloader.api.scene.SceneSnapshot;
import dev.luxloader.api.nativebridge.NativeBridge;
import dev.luxloader.api.pipeline.FrameControl;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.FrameSetup;
import dev.luxloader.api.pipeline.HostSceneMode;
import dev.luxloader.api.pipeline.PipelineDescriptor;
import dev.luxloader.api.pipeline.PipelineSettings;
import dev.luxloader.api.pipeline.RenderContext;
import dev.luxloader.api.pipeline.RenderPipeline;
import dev.luxloader.api.pipeline.Requirements;
import dev.luxloader.api.plugin.PipelineInfo;
import dev.luxloader.api.plugin.PipelinePlugin;
import dev.luxloader.api.plugin.PluginBootstrap;
import dev.luxloader.api.plugin.RenderDriver;
import dev.luxloader.api.vulkan.VulkanDispatch;
import dev.luxloader.core.capability.CapabilityRegistryImpl;
import dev.luxloader.core.capability.DeviceCapabilityProbe;
import dev.luxloader.core.config.JsonConfigStore;
import dev.luxloader.core.config.PipelineSettingsImpl;
import dev.luxloader.core.diag.DiagnosticsImpl;
import dev.luxloader.core.graph.FrameGraphCompiler;
import dev.luxloader.core.graph.FrameGraphExecution;
import dev.luxloader.core.graph.FrameGraphImpl;
import dev.luxloader.core.plugin.PluginLoader;
import dev.luxloader.core.profile.DeviceProfileStore;
import dev.luxloader.core.vulkan.VulkanApi;
import dev.luxloader.core.vulkan.VulkanDevice;
import dev.luxloader.core.vulkan.VulkanResourceProvider;
import dev.luxloader.nativebridge.FfmNativeBridge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates configuration, capabilities, plugins, pipelines and devices. Initialize
 * configuration/diagnostics, discover and install plugins, then call onLoad to collect
 * function-provider/instance requests and register pipelines. Create or attach the device, notify
 * dispatch readiness, run device probes, populate capabilities and activate the configured pipeline.
 * Pre-instance requests must precede creation; device probes require a ready device. Isolate
 * plugin/pipeline failures in state and diagnostics while keeping other components available.
 */
public final class RenderDriverImpl implements RenderDriver, HostServicesImpl.RuntimeContext {

    /** Loader version; keep aligned with the build. */
    public static final String VERSION = "0.1.0";

    private static final LuxMod LOADER_MOD = LuxMod.builder(
                    new GpuId("dev.luxloader", "loader"), "LuxLoader", VERSION)
            .authors("LuxLoader")
            .description(tr("Rendering pipeline loader"))
            .license("MIT")
            .build();

    private final Path configDirectory;
    /** Content directory alongside mods, containing plugins, caches and reports. */
    private final Path contentDirectory;

    /** Plugin IDs to disk data directories. Classpath plugins use classpath resources and have no disk directory. */
    private final Map<String, Path> pluginDataDirectories = new LinkedHashMap<>();

    /**
     * Current acquired host presentation image. Blitting plugin output here replaces the host's blit
     * without adding a copy. NONE leaves normal presentation intact.
     */
    private HostAdapter.HostPresentImage currentPresentImage = HostAdapter.HostPresentImage.NONE;

    /** Trace only startup frames to avoid filling bounded diagnostic logs with repeated output. */
    private static final long TRACE_FRAMES = 3;
    private long tracedFrames;

    /**
     * Whether the one-shot presentation pixel readback ran. Its blocking submit/wait must stay outside the
     * recurring frame path.
     */
    private boolean pixelProbed;

    /**
     * Independent flag for the presentation spatial-variance probe. Sharing pixelProbed previously
     * suppressed this probe after the source readback. Source pixels are sampled before copying; variance
     * inspects the presented texture afterward. Both require their own one-shot state.
     */
    private boolean presentVarianceProbed;

    /** Frames seen by {@link #blitToPresentTarget}; the probe waits for the world to load. */
    private int presentProbeFrames;

    /**
     * Skip this many frames before probing.
     *
     * <p>The first seconds are the loading screen, which is legitimately black — sampling
     * pixel (0,0) there says nothing about whether the pipeline produced a picture. The old
     * one-shot probe did exactly that and reported "black", which is indistinguishable from
     * "the clear landed". Waiting also means the world is actually being rendered.
     */
    private static final int PROBE_AFTER_FRAMES = 90;

    /**
     * Owned Vulkan instance used to locate the host physical device. Keep it alive until shutdown because
     * enumerated handles depend on its lifetime.
     */
    private VulkanApi.Probe hostProbe;

    /** Pipeline IDs disabled through the UI. */
    private final java.util.Set<String> userDisabledPipelines = new java.util.LinkedHashSet<>();
    private final AtomicBoolean initialized = new AtomicBoolean();

    private JsonConfigStore config;
    private CapabilityRegistryImpl capabilities;
    private DiagnosticsImpl diagnostics;
    private FfmNativeBridge nativeBridge;
    private VulkanDispatchImpl vulkanDispatch;
    private PluginLoader pluginLoader;

    private final List<PipelinePlugin> plugins = new ArrayList<>();
    private final Map<String, HostServicesImpl> hostServices = new LinkedHashMap<>();
    private final Map<GpuId, HostServicesImpl.Registration> registrations = new LinkedHashMap<>();
    private final Map<GpuId, PipelineInfo> pipelineStates = new LinkedHashMap<>();

    private VulkanDevice device;
    private VulkanResourceProvider pipelineResources;

    /**
     * Persistent device capabilities and failed-path history, including driver-specific pipeline creation
     * and presentation failures.
     */
    private DeviceProfileStore deviceProfiles;
    private RenderPipeline activePipeline;
    private GpuId activePipelineId;
    private GpuQueue graphicsQueue;
    private GpuQueue computeQueue;
    private GpuQueue transferQueue;
    private FrameSetup currentSetup = FrameSetup.forTest(1920, 1080, 0L);

    /**
     * Whether currentSetup has received actual host data. Treat the first update from test placeholder
     * dimensions as a resize/history reset; afterward advance frame state without redundant resets.
     */
    private boolean hostSetupSynced;
    private HostAdapter hostAdapter;
    private SceneSnapshot currentScene;
    private long sceneResourceRevision = Long.MIN_VALUE;

    /**
     * Scene contributor registration and its owning plugin. The owner comes from the registering
     * {@link HostServicesImpl}, never from the caller-supplied ID, so one plugin cannot register or
     * release another plugin's contribution. Entries are removed when their owner unloads.
     */
    private record SceneContribution(String ownerPluginId,
                                     dev.luxloader.api.scene.SceneContributor contributor) {
    }

    private final java.util.Map<GpuId, SceneContribution> sceneContributors = new java.util.LinkedHashMap<>();

    /** Contributor IDs whose failure was already reported, to keep a per-frame failure from flooding logs. */
    private final java.util.Set<GpuId> brokenSceneContributors = new java.util.LinkedHashSet<>();

    @Override
    public void onSceneContributorRegistered(String ownerPluginId, GpuId id,
                                             dev.luxloader.api.scene.SceneContributor contributor) {
        String owner = ownerPluginId == null ? "" : ownerPluginId;
        if (id == null || contributor == null) {
            throw new IllegalArgumentException("Scene contributor registration from plugin " + owner
                    + " requires both an ID and a contributor");
        }
        if (!dev.luxloader.api.scene.SceneOwnership.isOwnedBy(owner, id)) {
            throw new IllegalArgumentException("Plugin " + owner
                    + " cannot register scene contributor " + id
                    + "; declare an ID inside its own namespace");
        }
        SceneContribution previous = sceneContributors.putIfAbsent(id,
                new SceneContribution(owner, contributor));
        if (previous != null) {
            throw new IllegalArgumentException(previous.ownerPluginId().equals(owner)
                    ? "Duplicate scene contributor: " + id
                    : "Scene contributor " + id + " already belongs to plugin " + previous.ownerPluginId());
        }
        brokenSceneContributors.remove(id);
    }

    @Override
    public void onSceneContributorDeregistered(String ownerPluginId, GpuId id) {
        if (id == null) {
            return;
        }
        SceneContribution registered = sceneContributors.get(id);
        if (registered == null) {
            return;
        }
        if (!registered.ownerPluginId().equals(ownerPluginId == null ? "" : ownerPluginId)) {
            throw new IllegalArgumentException("Scene contributor " + id + " belongs to plugin "
                    + registered.ownerPluginId() + "; plugin " + ownerPluginId + " cannot release it");
        }
        sceneContributors.remove(id);
        brokenSceneContributors.remove(id);
    }
    private CameraParams previousFrameCamera;
    private String previousFrameDimension = "";

    // Per-frame execution state for diagnostics and tests.
    /** Most recently compiled frame graph. */
    private FrameGraphImpl lastFrameGraph;
    /** Previous compilation result. */
    private FrameGraph.Compiled lastCompiled;
    /** Previous final output, supplied as frame.history next frame. */
    private dev.luxloader.api.gpu.ImageHandle lastFrameOutput;
    /** Disabled failed passes: node ID to reason. */
    private final Map<String, String> failedPasses = new LinkedHashMap<>();
    private String lastFrameGraphFailure;
    /** Cumulative submitted command buffers. */
    private long submittedCommandBufferCount;
    /** Cumulative requested history resets. */
    private long historyResetCount;

    private final AtomicBoolean reloadRequested = new AtomicBoolean();
    private String reloadReason = "";
    private GpuId pendingSwitch;
    private String pendingSwitchReason = "";

    private long settingsRevision;
    private int consecutiveFrameFailures;

    public RenderDriverImpl(Path configDirectory) {
        this(configDirectory, null);
    }

    /**
     * Separates configuration (<game>/config/luxloader/luxloader.json) from content (<game>/luxloader
     * beside mods). Content contains pipelines, shader-cache, reports and a generated README.txt.
     * @param contentDirectory explicit content directory; null infers it from the standard configuration
     * layout
     */
    public RenderDriverImpl(Path configDirectory, Path contentDirectory) {
        this.configDirectory = configDirectory.toAbsolutePath();
        this.contentDirectory = contentDirectory != null
                ? contentDirectory.toAbsolutePath()
                : inferContentDirectory(this.configDirectory);
    }

    /**
     * Infers content from <game>/config/luxloader as <game>/luxloader. For other layouts, use a sibling of
     * the configuration directory rather than guessing an unrelated root.
     */
    private static Path inferContentDirectory(Path configDirectory) {
        Path parent = configDirectory.getParent();
        if (parent != null && "config".equals(String.valueOf(parent.getFileName()))) {
            Path gameDir = parent.getParent();
            if (gameDir != null) {
                return gameDir.resolve("luxloader");
            }
        }
        return configDirectory.resolveSibling("luxloader");
    }

    /** Root content directory for plugins, caches and reports. */
    public Path contentDirectory() {
        return contentDirectory;
    }

    // User pipeline enablement.

    /**
     * Disabled pipelines are excluded from automatic selection. Explicit activation still works and clears
     * the disabled flag.
     */
    @Override
    public boolean isPipelineEnabled(GpuId id) {
        return id != null && !userDisabledPipelines.contains(id.toString());
    }

    /** Persists UI enablement immediately so a shutdown cannot lose the user's selection. */
    @Override
    public boolean setPipelineEnabled(GpuId id, boolean enabled) {
        if (id == null) {
            return false;
        }
        String key = id.toString();
        boolean changed = enabled
                ? userDisabledPipelines.remove(key)
                : userDisabledPipelines.add(key);
        if (!changed) {
            return true;
        }
        if (config != null) {
            // Write through the explicit editor; root() is read-only and rejects mutation.
            config.editor("").set(LoaderConfig.DISABLED_PIPELINES,
                    String.join(",", userDisabledPipelines));
            config.save();
        }
        diagnostics.info((enabled ? tr("Enabled") : tr("Disabled")) + tr("Pipeline ") + key);
        // Update UI state immediately instead of waiting for automatic selection.
        if (!enabled) {
            HostServicesImpl.Registration registration = registrations.get(id);
            if (registration != null && !id.equals(activePipelineId)) {
                updateState(id, PipelineInfo.State.DISABLED,
                        tr("Disabled by the user (enable it in the LuxLoader pipeline screen)"));
            }
        }
        return true;
    }

    /** Restores disabled IDs from configuration. */
    private void loadDisabledPipelines() {
        userDisabledPipelines.clear();
        String raw = config == null ? "" : config.root()
                .getString(LoaderConfig.DISABLED_PIPELINES, "");
        for (String part : raw.split(",")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                userDisabledPipelines.add(trimmed);
            }
        }
    }

    /** Plugin root directory. */
    public Path pipelinesDirectory() {
        return contentDirectory.resolve("pipelines");
    }

    @Override
    public Optional<Path> pipelineDirectory() {
        return Optional.of(pipelinesDirectory());
    }

    @Override
    public Optional<ConfigSchema> pipelineOptionsSchema(GpuId id) {
        HostServicesImpl.Registration registration = registrations.get(id);
        HostServicesImpl host = registration == null ? null
                : hostServices.get(registration.providerPlugin());
        return host == null ? Optional.empty() : Optional.of(host.declareConfig());
    }

    @Override
    public Optional<ConfigView> pipelineOptions(GpuId id) {
        HostServicesImpl.Registration registration = registrations.get(id);
        return registration == null || config == null ? Optional.empty()
                : Optional.of(config.section("plugins."
                        + encodePluginId(registration.providerPlugin())));
    }

    @Override
    public boolean applyPipelineChoice(GpuId id, Map<String, Object> options) {
        if (config == null || (id != null && !registrations.containsKey(id))) {
            return false;
        }
        Map<String, Object> changes = options == null ? Map.of() : options;
        if (id == null && !changes.isEmpty()) {
            return false;
        }
        HostServicesImpl.Registration registration = id == null ? null : registrations.get(id);
        ConfigSchema schema = id == null ? null : pipelineOptionsSchema(id).orElse(null);
        Map<String, Object> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            ConfigOption<?> option = schema == null ? null : schema.find(change.getKey()).orElse(null);
            Object value = option == null ? null : option.coerce(change.getValue());
            if (value == null) {
                diagnostics.warn(tr("Invalid plugin settings: ") + change.getKey() + " = " + change.getValue());
                return false;
            }
            validated.put(change.getKey(), value);
        }
        if (registration != null && !validated.isEmpty()) {
            var editor = config.editor("plugins." + encodePluginId(registration.providerPlugin()));
            validated.forEach(editor::set);
        }
        var loader = config.editor("");
        loader.set(LoaderConfig.AUTO_ACTIVATE, false);
        loader.set(LoaderConfig.ACTIVE_PIPELINE, id == null ? "" : id.toString());
        if (id != null && userDisabledPipelines.remove(id.toString())) {
            loader.set(LoaderConfig.DISABLED_PIPELINES,
                    String.join(",", userDisabledPipelines));
        }
        config.save();
        if (config.lastError().isPresent()) {
            diagnostics.error(tr("Failed to save plugin selection: ") + config.lastError().get());
            return false;
        }
        requestReload(tr("In-game plugin/settings selection"));
        return true;
    }

    /**
     * Persistent device profiles exposed to adapters/tools for capability and historical-issue display;
     * null before initialization.
     */
    public DeviceProfileStore deviceProfiles() {
        return deviceProfiles;
    }

    /** Shader compilation cache directory. */
    public Path shaderCacheDirectory() {
        return contentDirectory.resolve("shader-cache");
    }

    @Override
    public String version() {
        return VERSION;
    }

    // Initialization.

    @Override
    public void initialize(DeviceRequest request) {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }
        DeviceRequest effective = request == null ? DeviceRequest.defaults() : request;

        try {
            // 1. Configuration and infrastructure.
            this.config = new JsonConfigStore(configDirectory.resolve("luxloader.json"));
            config.bindSchema("", LoaderConfig.schema());
            config.load();
            loadDisabledPipelines();
            if (config.lastError().isPresent()) {
                System.err.println(tr("[LuxLoader] Configuration read error: ") + config.lastError().get());
            }

            ConfigView root = config.root();
            boolean verbose = root.getBoolean(LoaderConfig.VERBOSE, false);
            int maxLogLines = root.getInt(LoaderConfig.MAX_LOG_LINES, 4000);
            Path reportDir = contentDirectory.resolve(
                    root.getString(LoaderConfig.REPORT_DIRECTORY, "reports"));

            // Default plugin caches to the content directory through the shared system property, preserving an explicit -Dluxloader.cacheDir override.
            if (System.getProperty("luxloader.cacheDir") == null) {
                System.setProperty("luxloader.cacheDir", contentDirectory.toString());
            }

            this.diagnostics = new DiagnosticsImpl(reportDir, "loader", verbose, maxLogLines);
            this.capabilities = new CapabilityRegistryImpl();
            // Device history belongs in content beside pipelines, independent of configuration-directory changes.
            this.deviceProfiles = new DeviceProfileStore(
                    contentDirectory.resolve("device-profiles.json"));
            for (String warning : deviceProfiles.warnings()) {
                diagnostics.warn(warning);
            }
            this.nativeBridge = new FfmNativeBridge();
            this.vulkanDispatch = new VulkanDispatchImpl();
            this.pluginLoader = new PluginLoader();

            diagnostics.fact("loader.version", VERSION);
            diagnostics.fact("loader.configDir", configDirectory.toString());
            diagnostics.fact("loader.jvm", System.getProperty("java.version", "?"));
            diagnostics.fact("loader.os", System.getProperty("os.name", "?")
                    + " / " + System.getProperty("os.arch", "?"));
            diagnostics.fact("native.bridge", nativeBridge.implementationName());
            diagnostics.fact("native.platform", nativeBridge.platformTag());
            diagnostics.fact("config.firstRun", String.valueOf(config.isFirstRun()));

            if (!root.getBoolean(LoaderConfig.ENABLED, true)) {
                diagnostics.info(tr("LuxLoader is disabled in configuration; rendering remains unchanged"));
                return;
            }

            // Configured default function provider; plugins can request another during onLoad.
            String configuredProvider = root.getString(LoaderConfig.CUSTOM_FUNCTION_PROVIDER, "");
            if (!configuredProvider.isBlank()) {
                vulkanDispatch.requestFunctionProvider(configuredProvider, tr("Default function source from configuration"));
            }
            if (root.getBoolean(LoaderConfig.FORCE_REAL_LOADER, false)) {
                vulkanDispatch.forceRealLoader(true);
            }

            // 2. Discover classpath, content, legacy and configured extra packages through the same path used by reload.
            List<PipelinePlugin> sorted = discoverAllPlugins();

            // 3. Install and bind HostServices without registration. A plugin that fails after its
            // services object exists is rolled back immediately instead of waiting for close().
            for (PipelinePlugin plugin : sorted) {
                try {
                    LuxMod mod = plugin.mod();
                    if (mod == null) {
                        diagnostics.error(tr("Plugin ") + plugin.getClass().getName() + tr(" returned null from mod(); skipped"));
                        continue;
                    }
                    HostServicesImpl host = new HostServicesImpl(
                            mod, capabilities, diagnostics.scoped(mod.id().toString()),
                            nativeBridge, this);
                    host.setGameVersion(gameVersion());
                    hostServices.put(mod.id().toString(), host);
                    plugins.add(plugin);
                } catch (RuntimeException | LinkageError e) {
                    failPluginLoad(plugin, tr("installation failed"), e);
                }
            }

            // 4. onLoad: register pipelines and collect Vulkan requests.
            for (PipelinePlugin plugin : List.copyOf(plugins)) {
                HostServicesImpl host = hostServices.get(pluginIdOf(plugin));
                try {
                    ConfigSchema schema = plugin.declareConfig();
                    config.bindSchema("plugins." + encodePluginId(pluginIdOf(plugin)), schema);
                    applySchemaDefaults("plugins." + encodePluginId(pluginIdOf(plugin)), schema);
                    host.bindConfig(schema, settingsFor(pluginIdOf(plugin), schema));

                    PluginBootstrap bootstrap = bootstrapFor(host);
                    plugin.onLoad(bootstrap);
                } catch (Exception | LinkageError e) {
                    // Isolate checked plugin entry-point exceptions so other plugins and the loader remain usable.
                    failPluginLoad(plugin, tr("onLoad failed"), e);
                }
            }

            // 5. Create or attach the device.
            initializeDevice(effective);

            // 6. Probe the device and register capabilities. Iterate a snapshot so a probe failure
            // that removes its own instance cannot disturb the remaining probes.
            if (device != null) {
                for (PipelinePlugin plugin : List.copyOf(plugins)) {
                    HostServicesImpl host = hostServices.get(pluginIdOf(plugin));
                    try {
                        plugin.probe(bootstrapFor(host));
                    } catch (RuntimeException | LinkageError e) {
                        diagnostics.error(tr("Plugin ") + pluginIdOf(plugin) + tr(" probe failed"), e);
                    }
                }
            }

            // 7. Populate device capabilities.
            DeviceCapabilityProbe.probeInto(capabilities, device);
            if (device != null) {
                DeviceCapabilityProbe.registerQueueCapabilities(capabilities,
                        device.computeQueue().dedicated(), device.transferQueue().dedicated());
            }

            // Register diagnostic sections.
            installDiagnosticSections();

            // 8. Activate a pipeline.
            activateConfiguredPipeline();

            diagnostics.info(tr("LuxLoader initialized: plugins ") + plugins.size()
                    + tr(", registered pipelines ") + registrations.size()
                    + tr(", active ") + activePipelineId);

        } catch (RuntimeException | LinkageError e) {
            if (diagnostics != null) {
                diagnostics.error(tr("LuxLoader initialization failed (loader remains idle; game continues)"), e);
            } else {
                System.err.println(tr("[LuxLoader] Initialization failed: ") + e);
                e.printStackTrace();
            }
            // Restore standby on initialization failure so the game can start.
            teardownQuietly();
        }
    }

    /**
     * Applies missing schema defaults and migrates historical defaults. Use the store's nested-path has
     * lookup, not the editor view's flat containsKey: flat lookup treats every dotted path as absent and
     * silently overwrites configured values with defaults.
     */
    private void applySchemaDefaults(String section, ConfigSchema schema) {
        // Migrate existing historical defaults before filling missing keys. Plugin schemas are bound here, after the store's initial load.
        config.migrateSection(section, schema);
        var editor = config.editor(section);
        String prefix = section == null || section.isEmpty() ? "" : section + ".";
        for (var option : schema.options()) {
            if (!config.has(prefix + option.path())) {
                option.writeDefault(editor);
            }
        }
    }

    /**
     * Encodes plugin IDs as one configuration key by replacing dots/colons with underscores, preventing
     * dotted IDs from creating unintended nested sections.
     */
    static String encodePluginId(String pluginId) {
        if (pluginId == null) {
            return "";
        }
        return pluginId.replace('.', '_').replace(':', '_');
    }

    private PluginBootstrap bootstrapFor(HostServicesImpl host) {
        String pluginId = host.mod() == null ? "" : host.mod().id().toString();
        return new PluginBootstrapImpl(host, vulkanDispatch, nativeBridge,
                () -> device,
                () -> device == null ? null : device.capabilities(),
                () -> hostServices.keySet().stream().toList(),
                gameVersion(),
                pluginDataDirectories.get(pluginId));
    }

    /** Plugin ID, falling back to its class name if mod() throws. */
    private static String pluginIdOf(PipelinePlugin plugin) {
        try {
            LuxMod mod = plugin.mod();
            return mod == null ? plugin.getClass().getName() : mod.id().toString();
        } catch (RuntimeException e) {
            return plugin.getClass().getName();
        }
    }

    /** Creates content directories and instructions explaining where to install pipeline packages. */
    private void prepareDirectories(Path pipelinesDir) {
        try {
            java.nio.file.Files.createDirectories(pipelinesDir);
            java.nio.file.Files.createDirectories(shaderCacheDirectory());
            Path readme = contentDirectory.resolve("README.txt");
            if (!java.nio.file.Files.exists(readme)) {
                java.nio.file.Files.writeString(readme, contentReadme());
            }
        } catch (java.io.IOException e) {
            diagnostics.warn(tr("Failed to create content directory (classpath plugins can still load): ") + e);
        }
    }

    /** Installation notes use the selected language at file creation time. */
    private static String contentReadme() throws java.io.IOException {
        String resource = "content-readme_" + dev.luxloader.api.i18n.Messages.language() + ".txt";
        try (var stream = RenderDriverImpl.class.getResourceAsStream(resource)) {
            if (stream == null) throw new java.io.IOException("Missing bundled resource: " + resource);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * Creates or attaches Vulkan with three valid outcomes: ready, waiting for attachDevice when using a
     * host device, or standby with a reported creation failure.
     */
    private void initializeDevice(DeviceRequest request) {
        if (request.useExistingDevice()) {
            diagnostics.info(tr("Configured to attach an external device: waiting for Minecraft's Vulkan device. ")
                    + tr("Pipelines remain inactive until the device is ready."));
            capabilities.forceRegister(new dev.luxloader.api.capability.CapabilityDescriptor(
                    dev.luxloader.api.capability.CapabilityDescriptor.Ids.VULKAN_BACKEND,
                    CapabilityLevel.UNSUPPORTED, tr("Vulkan backend"), "loader",
                    tr("Waiting for the host device (useExistingDevice=true)"),
                    Map.of(), System.nanoTime()));
            return;
        }

        if (!VulkanApi.isAvailable()) {
            String reason = VulkanApi.unavailableReason();
            diagnostics.warn(tr("Vulkan unavailable on this system: ") + reason
                    + tr(". Loader remains idle and the game continues normally."));
            diagnostics.fact("vulkan.unavailableReason", reason);
            capabilities.forceRegister(dev.luxloader.api.capability.CapabilityDescriptor.unsupported(
                    dev.luxloader.api.capability.CapabilityDescriptor.Ids.VULKAN_BACKEND,
                    "loader", tr("Vulkan unavailable on this system: ") + reason));
            return;
        }

        diagnostics.fact("vulkan.loaderVersion", VulkanApi.formatVersion(
                VulkanApi.loaderInstanceVersion()));
        diagnostics.fact("vulkan.functionProvider",
                vulkanDispatch.requestedProvider().orElse(
                        vulkanDispatch.isRealLoaderForced() ? tr("RenderDriverImpl.d9baff1838", "System loader (forced)") : tr("System loader")));

        VulkanDevice created = VulkanDevice.create(
                request.applicationName(), request.preferDiscreteGpu(), diagnostics);
        if (created == null) {
            // Unavailable Vulkan is expected on another backend/unsupported hardware, so report a warning rather than an error.
            diagnostics.warn(tr("Could not create a Vulkan device. If the host is not using Vulkan (OpenGL remains the default in 26.3), ")
                    + tr("this is expected; the loader will remain idle."));
            capabilities.forceRegister(dev.luxloader.api.capability.CapabilityDescriptor.unsupported(
                    dev.luxloader.api.capability.CapabilityDescriptor.Ids.VULKAN_BACKEND,
                    "loader", tr("Could not create Vulkan device")));
            return;
        }

        adoptDeviceInternal(created, true);
    }

    private void adoptDeviceInternal(VulkanDevice adopted, boolean ownDevice) {
        this.device = adopted;
        this.pipelineResources = new VulkanResourceProvider(adopted, "pipeline", 0L);
        this.graphicsQueue = adopted.graphicsQueue();
        this.computeQueue = adopted.computeQueue();
        this.transferQueue = adopted.transferQueue();

        // Separate physical support from host-enabled extensions; adopted capabilities govern actual pipeline eligibility.
        vulkanDispatch.reportAvailableDeviceExtensions(hostProbe == null
                ? adopted.capabilities().extensions()
                : hostProbe.capabilities().extensions());
        vulkanDispatch.advanceToBeforeDevice();
        vulkanDispatch.notifyDispatchReady(adopted.nativeInstanceHandle(), adopted.nativeHandle(),
                adopted.physicalDevice().address(), name -> {
            // Resolve native entry points through LWJGL for plugin SDK integrations.
            try {
                return org.lwjgl.vulkan.VK10.vkGetDeviceProcAddr(
                        adopted.vkDevice(), name);
            } catch (Throwable t) {
                return 0L;
            }
        });

        GpuCapabilities caps = adopted.capabilities();
        diagnostics.info(tr("Device ready: ") + caps.deviceName() + " / Vulkan " + caps.apiVersionString()
                + tr(" / VRAM ") + caps.deviceMemoryGiB()
                + (adopted.computeQueue().dedicated() ? tr(" / independent compute queue") : tr(" / no independent compute queue")));
        diagnostics.fact("device.name", caps.deviceName());
        diagnostics.fact("device.vendor", caps.vendor().displayName());
        diagnostics.fact("device.apiVersion", caps.apiVersionString());
        diagnostics.fact("device.vram", caps.deviceMemoryGiB());
        diagnostics.fact("device.extensions", Integer.toString(caps.extensions().size()));
        diagnostics.fact("device.asyncCompute", String.valueOf(adopted.computeQueue().dedicated()));
        diagnostics.fact("device.rayTracing", String.valueOf(caps.supportsRayTracing()));
        diagnostics.fact("device.ownDevice", String.valueOf(ownDevice));

        // Create this run's device profile before later probing/activation can report failures.
        if (deviceProfiles != null) {
            adopted.setIssueReporter(deviceProfiles::noteIssue);
            DeviceProfileStore.Profile profile = deviceProfiles.record(caps);
            diagnostics.fact("device.profileKey", profile.key());
            diagnostics.fact("device.profileRuns", Integer.toString(profile.runCount()));
            if (!profile.issues().isEmpty()) {
                // Report historical issues at startup for driver comparisons and recurring fault diagnosis.
                diagnostics.warn(tr("This device (profile key ") + profile.key() + tr(") has ")
                        + profile.issues().size() + tr(" known issue categories; see Device profiles in the diagnostic report"));
            }
        }
    }

    /**
     * Refreshes the profile on demand because adapters/plugins can change capabilities after initial
     * probing, including revocation.
     */
    private void syncDeviceProfile() {
        if (deviceProfiles == null || device == null || capabilities == null) {
            return;
        }
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (dev.luxloader.api.capability.CapabilityDescriptor d : capabilities.all()) {
            // Keep verbose extension enumeration separate from the concise profile.
            if (!d.id().startsWith("ext.")) {
                snapshot.put(d.id(), d.level().name());
            }
        }
        deviceProfiles.capabilities(snapshot);
    }

    /**
     * Adopts host-created Vulkan handles without ownership.
     * @param vkDeviceHandle native device
     * @param vkPhysicalDeviceHandle native physical device
     * @param deviceCapabilities observed capabilities
     * @return whether attachment succeeded
     */
    public boolean attachDevice(long vkDeviceHandle, long vkPhysicalDeviceHandle,
                               GpuCapabilities deviceCapabilities) {
        if (device != null) {
            diagnostics.warn(tr("Device already attached; ignoring this request"));
            return false;
        }
        if (vkDeviceHandle == 0L) {
            diagnostics.error(tr("Device attachment failed: handle is zero"));
            return false;
        }
        VulkanDevice adopted = VulkanDevice.adopt(hostProbe == null ? null : hostProbe.instance(), vkDeviceHandle, vkPhysicalDeviceHandle,
                deviceCapabilities, diagnostics);
        if (adopted == null) {
            diagnostics.error(tr("Device attachment failed: cannot wrap external handles"));
            return false;
        }
        adoptDeviceInternal(adopted, false);
        // Run deferred probes after device readiness. Snapshot the list so one probe cannot disturb
        // the remaining iterations.
        for (PipelinePlugin plugin : List.copyOf(plugins)) {
            HostServicesImpl host = hostServices.get(pluginIdOf(plugin));
            try {
                plugin.probe(bootstrapFor(host));
            } catch (RuntimeException | LinkageError e) {
                diagnostics.error(tr("Plugin ") + pluginIdOf(plugin) + tr(" probe failed"), e);
            }
        }
        DeviceCapabilityProbe.probeInto(capabilities, device);
        DeviceCapabilityProbe.registerQueueCapabilities(capabilities,
                device.computeQueue().dedicated(), device.transferQueue().dedicated());
        installDiagnosticSections();
        activateConfiguredPipeline();
        return true;
    }

    private void installDiagnosticSections() {
        diagnostics.section("Device capabilities", () -> {
            if (device == null) {
                return tr("  (Device not ready)");
            }
            GpuCapabilities caps = device.capabilities();
            StringBuilder sb = new StringBuilder();
            caps.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v)
                    .append(System.lineSeparator()));
            sb.append(tr("  Queues: ")).append(device.graphicsQueue().describe())
                    .append(" / ").append(device.computeQueue().describe())
                    .append(" / ").append(device.transferQueue().describe())
                    .append(System.lineSeparator());
            return sb.toString();
        });
        diagnostics.section("Capability registry", capabilities::describeTable);
        // Report reclamation failures: gradual leaks can reduce performance without causing a crash.
        diagnostics.section("Vulkan retirement and waits", () -> {
            if (device == null) {
                return tr("  (No device)");
            }
            var vk = (dev.luxloader.core.vulkan.VulkanCommands) device.commands();
            StringBuilder sb = new StringBuilder();
            sb.append(tr("  Whole-queue waits: ")).append(vk.wholeQueueWaitCount());
            sb.append(tr(" (should reach zero after fence-based retirement; nonzero indicates a temporary wait path)"));
            sb.append(System.lineSeparator());
            sb.append(tr("  Unsubmitted recordings: ")).append(vk.pendingRecordingCount());
            return sb.toString();
        });
        diagnostics.section("Vulkan retirement failures", () -> {
            java.util.List<String> failures = device == null ? java.util.List.of()
                    : ((dev.luxloader.core.vulkan.VulkanCommands) device.commands()).recyclingFailures();
            return failures.isEmpty() ? tr("  (none)")
                    : failures.stream().map(f -> "  " + f)
                            .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        });
        diagnostics.section("Device profiles", () -> {
            if (deviceProfiles == null) {
                return tr("  (Disabled)");
            }
            // Synchronize capabilities before exporting the current report.
            syncDeviceProfile();
            return deviceProfiles.describeCurrent();
        });
        diagnostics.section("Vulkan loader entry points", vulkanDispatch::describe);
        diagnostics.section("Registered pipelines", this::describePipelines);
        diagnostics.section("Plugins", this::describePlugins);
        diagnostics.section("Configuration issues", () -> {
            if (config.ignoredKeys().isEmpty()) {
                return tr("  (none)");
            }
            StringBuilder sb = new StringBuilder();
            for (var ignored : config.ignoredKeys()) {
                sb.append("  ").append(ignored.describe()).append(System.lineSeparator());
            }
            return sb.toString();
        });
    }

    private String describePipelines() {
        if (registrations.isEmpty()) {
            return tr("  (none)");
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : registrations.entrySet()) {
            PipelineInfo info = pipelineStates.get(entry.getKey());
            PipelineDescriptor d = entry.getValue().descriptor();
            sb.append("  ").append(entry.getKey())
                    .append("  ").append(d.name()).append(' ').append(d.version())
                    .append("  [").append(info == null ? "UNKNOWN" : info.state()).append(']')
                    .append(tr("  Frame ownership=")).append(d.frameOwnership())
                    .append(System.lineSeparator());
            if (info != null && !info.detail().isEmpty()) {
                sb.append(tr("      Details: ")).append(info.detail()).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    private String describePlugins() {
        if (plugins.isEmpty()) {
            return tr("  (none)");
        }
        StringBuilder sb = new StringBuilder();
        for (PipelinePlugin plugin : plugins) {
            LuxMod mod = plugin.mod();
            HostServicesImpl host = hostServices.get(mod.id().toString());
            sb.append("  ").append(mod.id()).append("  ").append(mod.name())
                    .append(' ').append(mod.version()).append(System.lineSeparator());
            if (host != null) {
                for (String warning : host.warnings()) {
                    sb.append(tr("      Warning: ")).append(warning).append(System.lineSeparator());
                }
            }
        }
        return sb.toString();
    }

    // Pipeline activation.

    /** Automatically selects and activates by priority. */
    private void autoSelectAndActivate() {
        if (device == null) {
            return;
        }
        boolean allowExperimental = config.root().getBoolean(LoaderConfig.ALLOW_EXPERIMENTAL, false);
        List<HostServicesImpl.Registration> candidates = new ArrayList<>();
        for (HostServicesImpl.Registration registration : registrations.values()) {
            if (userDisabledPipelines.contains(registration.id().toString())) {
                // Disabled entries skip automatic selection but remain explicitly activatable.
                updateState(registration.id(), PipelineInfo.State.DISABLED,
                        tr("Disabled by the user (enable it in the LuxLoader pipeline screen)"));
                continue;
            }
            if (registration.descriptor().experimental() && !allowExperimental) {
                updateState(registration.id(), PipelineInfo.State.REGISTERED,
                        tr("Experimental; enable allowExperimental in configuration"));
                continue;
            }
            Requirements.Verdict verdict = verify(registration.descriptor());
            if (verdict.passed()) {
                candidates.add(registration);
            } else {
                updateState(registration.id(), PipelineInfo.State.REJECTED, verdict.explain());
            }
        }
        if (candidates.isEmpty()) {
            diagnostics.info(tr("No eligible pipeline to activate (registered: ") + registrations.size() + tr("RenderDriverImpl.624bee9cbd", ")"));
            return;
        }
        candidates.sort(Comparator
                .comparingInt((HostServicesImpl.Registration r) -> r.descriptor().priority()).reversed()
                .thenComparing(HostServicesImpl.Registration::id));
        HostServicesImpl.Registration best = candidates.get(0);
        activatePipeline(best.id(), tr("Automatically select the highest-priority available pipeline"));
    }

    /** Startup, attachment and reload honor the same persisted selection. */
    private void activateConfiguredPipeline() {
        if (device == null) {
            return;
        }
        refreshPipelineAvailability();
        ConfigView root = config.root();
        String selected = root.getString(LoaderConfig.ACTIVE_PIPELINE, "");
        boolean automatic = root.getBoolean(LoaderConfig.AUTO_ACTIVATE, true);
        if (!selected.isBlank()) {
            GpuId id = GpuId.tryParse(selected);
            if (id == null) {
                diagnostics.error(tr("Configured activePipeline is not a valid GpuId: ") + selected);
                clearConfiguredPipeline(tr("Invalid pipeline identifier in configuration"));
            } else if (activatePipeline(id, tr("From configuration"))) {
                return;
            } else {
                diagnostics.warn(tr("Configured pipeline cannot activate: ") + id);
                clearConfiguredPipeline(tr("Pipeline ") + id + tr(" activation failed"));
            }
            return;
        }
        if (automatic) {
            autoSelectAndActivate();
        }
    }

    /** A failed explicit selection must not silently activate another plugin on restart. */
    private void clearConfiguredPipeline(String reason) {
        if (config == null) {
            return;
        }
        var editor = config.editor("");
        editor.set(LoaderConfig.AUTO_ACTIVATE, false);
        editor.set(LoaderConfig.ACTIVE_PIPELINE, "");
        config.save();
        diagnostics.error(tr("Plugin mode disabled: ") + reason
                + tr(". Enable a plugin again in the selector; vanilla rendering is currently active."));
        config.lastError().ifPresent(error ->
                diagnostics.error(tr("Failed to save disabled state: ") + error));
    }

    @Override
    public boolean activatePipeline(GpuId id, String reason) {
        if (id == null) {
            return false;
        }
        HostServicesImpl.Registration registration = registrations.get(id);
        if (registration == null) {
            diagnostics.error(tr("Activation failed: unregistered pipeline ") + id);
            return false;
        }
        // Explicit activation clears the disabled flag to reflect the user's selection.
        if (userDisabledPipelines.contains(id.toString())) {
            setPipelineEnabled(id, true);
        }
        if (device == null) {
            diagnostics.warn(tr("Activation failed: device not ready (pipeline ") + id + "）");
            updateState(id, PipelineInfo.State.REJECTED, tr("Device not ready"));
            return false;
        }
        if (id.equals(activePipelineId)) {
            diagnostics.debug(tr("Pipeline ") + id + tr(" is already active; ignoring duplicate activation"));
            return true;
        }

        // Validate prerequisites.
        Requirements.Verdict verdict = verify(registration.descriptor());
        if (!verdict.passed()) {
            diagnostics.error(tr("Activation rejected: ") + id + " —— " + verdict.explain());
            updateState(id, PipelineInfo.State.REJECTED, verdict.explain());
            return false;
        }

        diagnostics.info(tr("Activating pipeline ") + id + "（" + reason + "）");
        updateState(id, PipelineInfo.State.ACTIVATING, reason);

        // Drain in-flight work before closing the old pipeline.
        deactivatePipelineInternal(tr("Switch to ") + id);

        try {
            RenderPipeline pipeline = registration.factory().get();
            if (pipeline == null) {
                throw new IllegalStateException(tr("Pipeline factory returned null"));
            }

            // Release the entire pipeline resource scope when switching.
            pipelineResources = new VulkanResourceProvider(device, "pipeline:" + id, 0L);

            ConfigSchema schema = hostServices.get(registration.providerPlugin()) == null
                    ? ConfigSchema.builder().build()
                    : hostServices.get(registration.providerPlugin()).declareConfig();
            PipelineSettings settings = settingsFor(registration.providerPlugin(), schema);

            RenderContext ctx = new RenderContext(
                    hostServices.get(registration.providerPlugin()),
                    device, pipelineResources, graphicsQueue, computeQueue, transferQueue, currentSetup);

            // Flush step markers immediately because native crashes may bypass Java exceptions and deferred logs.
            diagnostics.info(tr("[Step] Pipeline initialization started: ") + id
                    + tr(" (compiling shaders, allocating textures and creating compute pipelines)"));
            // Own the instance before initialization so partial failures also close it.
            this.activePipeline = pipeline;
            pipeline.initialize(ctx);
            // Initialization may record uploads or native SDK work. Finish that batch
            // before the first Minecraft-owned frame encoder can start.
            device.commands().flush(graphicsQueue, List.of(), List.of());
            diagnostics.info(tr("[Step] Pipeline initialization returned: ") + id);

            this.activePipeline = pipeline;
            this.activePipelineId = id;
            this.consecutiveFrameFailures = 0;
            updateState(id, PipelineInfo.State.ACTIVE, reason);
            diagnostics.info(tr("Pipeline activated: ") + registration.descriptor().describe());
            return true;

        } catch (Exception | LinkageError e) {
            // Never submit a partially initialized pipeline's remaining commands.
            if (device.commands() instanceof dev.luxloader.core.vulkan.VulkanCommands commands) {
                commands.discardPendingRecordings();
            }
            diagnostics.error(tr("Pipeline initialization failed: ") + id + tr(" -- reverted to vanilla rendering"), e);
            updateState(id, PipelineInfo.State.FAILED,
                    DiagnosticsImpl.stackTrace(e));
            teardownActivePipeline();
            return false;
        }
    }

    @Override
    public void deactivatePipeline(String reason) {
        deactivatePipelineInternal(reason);
        diagnostics.info(tr("Pipeline disabled; returning to vanilla rendering (") + reason + "）");
    }

    private void deactivatePipelineInternal(String reason) {
        GpuId previous = activePipelineId;
        teardownActivePipeline();
        if (previous != null) {
            updateState(previous, PipelineInfo.State.CLOSED, reason);
        }
    }

    /**
     * Detects host destruction without the loader's release hook. The adapter should invoke
     * onHostDeviceClosing before VulkanDevice.close; absent notification on a borrowed device means native
     * cleanup may be too late.
     */
    private boolean hostDeviceAlreadyDestroyed() {
        return device != null && !device.isOwned() && !hostDeviceReleased;
    }

    /**
     * Drops local Vulkan-holding references without native destruction when the host device is already
     * gone. Even waitIdle would use an invalid handle. Plugin onUnload still runs; plugins doing native
     * cleanup there remain dependent on a correctly installed release hook.
     */
    private void abandonGpuObjects() {
        activePipeline = null;
        activePipelineId = null;
        pipelineResources = null;
    }

    /** Closes the active pipeline and releases its scoped resources. */
    private void teardownActivePipeline() {
        if (activePipeline != null) {
            try {
                if (device != null) {
                    // Wait for GPU idle before releasing resources still referenced by in-flight work.
                    device.waitIdle();
                    if (device.commands() instanceof dev.luxloader.core.vulkan.VulkanCommands commands) {
                        commands.retireHostFramesAfterIdle();
                    }
                }
                activePipeline.close();
            } catch (RuntimeException e) {
                diagnostics.error(tr("Pipeline close failed (ignored; continuing cleanup)"), e);
            }
            activePipeline = null;
        }
        activePipelineId = null;
        if (pipelineResources != null) {
            try {
                pipelineResources.closeAll();
            } catch (RuntimeException e) {
                diagnostics.error(tr("Failed to release pipeline resources"), e);
            }
            pipelineResources = null;
        }
    }

    private void updateState(GpuId id, PipelineInfo.State state, String detail) {
        HostServicesImpl.Registration registration = registrations.get(id);
        if (registration == null) {
            return;
        }
        PipelineInfo info = new PipelineInfo(id, registration.descriptor(),
                registration.providerPlugin(), state, detail,
                pipelineResources == null ? 0L : pipelineResources.usedVramBytes());
        pipelineStates.put(id, info);
    }

    // Reloading.

    /**
     * Shared discovery path for initialization/reload: classpath, content pipelines, legacy location and
     * optional extra directories. A previous duplicate implementation interpreted pluginDirectory
     * differently on reload and made valid content packages disappear.
     */
    private List<PipelinePlugin> discoverAllPlugins() {
        // Deduplicate by plugin ID with classpath precedence.
        List<PipelinePlugin> discovered = pluginLoader.discoverOnClasspath(
                RenderDriverImpl.class.getClassLoader());

        Path pipelinesDir = pipelinesDirectory();
        List<PluginLoader.Discovered> packages = pluginLoader.discoverPackages(
                pipelinesDir, RenderDriverImpl.class.getClassLoader());

        // Support the legacy config/luxloader/plugins directory.
        Path legacyDir = configDirectory.resolve("plugins");
        if (!legacyDir.equals(pipelinesDir) && java.nio.file.Files.isDirectory(legacyDir)) {
            List<PluginLoader.Discovered> legacy = pluginLoader.discoverPackages(
                    legacyDir, RenderDriverImpl.class.getClassLoader());
            if (!legacy.isEmpty()) {
                diagnostics.warn(tr("Found plugins at legacy location ") + legacyDir + tr("RenderDriverImpl.5ab16be8dd", ": ") + legacy.size()
                        + tr(" plugins. Consider moving them to ") + pipelinesDir + tr(" (beside mods/)"));
                packages = new ArrayList<>(packages);
                packages.addAll(legacy);
            }
        }

        // Optional configured plugin directories.
        String extraDir = config.root().getString(LoaderConfig.PLUGIN_DIRECTORY, "");
        if (!extraDir.isBlank()) {
            Path extra = Path.of(extraDir);
            if (!extra.isAbsolute()) {
                extra = contentDirectory.resolve(extra);
            }
            if (java.nio.file.Files.isDirectory(extra)) {
                List<PluginLoader.Discovered> fromExtra = pluginLoader.discoverPackages(
                        extra, RenderDriverImpl.class.getClassLoader());
                diagnostics.info(tr("Additional plugin directory ") + extra + tr(" supplies ") + fromExtra.size() + tr(" plugins"));
                packages = new ArrayList<>(packages);
                packages.addAll(fromExtra);
            } else {
                diagnostics.warn(tr("Configured additional plugin directory does not exist: ") + extra);
            }
        }

        discovered = new ArrayList<>(discovered);
        discovered.addAll(packages.stream().map(PluginLoader.Discovered::plugin).toList());
        for (PluginLoader.Discovered packagePlugin : packages) {
            pluginDataDirectories.putIfAbsent(
                    pluginIdOf(packagePlugin.plugin()), packagePlugin.dataDirectory());
        }

        prepareDirectories(pipelinesDir);
        List<PipelinePlugin> sorted = pluginLoader.sortByDependencies(discovered);

        diagnostics.info(tr("Discovered ") + sorted.size() + tr(" plugins")
                + tr(" (content directory ") + pipelinesDir + tr(" supplies ") + packages.size() + tr("RenderDriverImpl.624bee9cbd", ")")
                + (pluginLoader.errors().isEmpty() ? "" : "（" + pluginLoader.errors().size() + tr(" errors)")));
        for (String error : pluginLoader.errors()) {
            diagnostics.error(tr("Plugin discovery issue: ") + error);
        }
        for (String skipped : pluginLoader.skipped()) {
            diagnostics.warn(tr("Plugin skipped: ") + skipped);
        }
        for (PluginLoader.Discovered packagePlugin : packages) {
            diagnostics.fact("plugin." + pluginIdOf(packagePlugin.plugin()) + ".source",
                    packagePlugin.source());
        }
        return sorted;
    }

    @Override
    public void reload(String reason) {
        if (!initialized.get()) {
            return;
        }
        diagnostics.info(tr("Starting reload: ") + reason);
        diagnostics.fact("loader.lastReloadReason", reason);

        deactivatePipelineInternal(tr("Reload"));

        // Release the replaced plugin instances before anything is re-registered, so a previous
        // owner can neither keep contributing nor collide with the new instance's registrations.
        unloadPlugins(tr("Reload"));

        // Retain the expensive device and reset only plugins/pipelines.
        plugins.clear();
        hostServices.clear();
        registrations.clear();
        pipelineStates.clear();

        config.reload();
        loadDisabledPipelines();
        capabilities.clear();
        if (device != null) {
            DeviceCapabilityProbe.probeInto(capabilities, device);
            DeviceCapabilityProbe.registerQueueCapabilities(capabilities,
                    device.computeQueue().dedicated(), device.transferQueue().dedicated());
        }

        // Use the same discovery path as initialization so reload retains packages installed under content/pipelines.
        try {
            // Installation failures are rolled back here as well: an instance that fails before or
            // during declaration must not stay in the active set until the loader closes.
            for (PipelinePlugin plugin : discoverAllPlugins()) {
                try {
                    LuxMod mod = plugin.mod();
                    if (mod == null) {
                        diagnostics.error(tr("Plugin ") + plugin.getClass().getName() + tr(" returned null from mod(); skipped"));
                        continue;
                    }
                    HostServicesImpl host = new HostServicesImpl(mod, capabilities,
                            diagnostics.scoped(mod.id().toString()), nativeBridge, this);
                    host.setGameVersion(gameVersion());
                    plugins.add(plugin);
                    hostServices.put(mod.id().toString(), host);
                    ConfigSchema schema = plugin.declareConfig();
                    config.bindSchema("plugins." + encodePluginId(mod.id().toString()), schema);
                    host.bindConfig(schema, settingsFor(mod.id().toString(), schema));
                } catch (RuntimeException | LinkageError e) {
                    failPluginLoad(plugin, tr("installation failed"), e);
                }
            }

            // Collect onLoad requests; with an existing device, late Vulkan requests become warnings.
            for (PipelinePlugin plugin : List.copyOf(plugins)) {
                try {
                    plugin.onLoad(bootstrapFor(hostServices.get(pluginIdOf(plugin))));
                } catch (Exception | LinkageError e) {
                    // Isolate checked plugin entry-point exceptions so other plugins and the loader remain usable.
                    failPluginLoad(plugin, tr("onLoad failed"), e);
                }
            }
            // probe
            if (device != null) {
                for (PipelinePlugin plugin : List.copyOf(plugins)) {
                    try {
                        plugin.probe(bootstrapFor(hostServices.get(pluginIdOf(plugin))));
                    } catch (RuntimeException | LinkageError e) {
                        diagnostics.error(tr("Plugin ") + pluginIdOf(plugin) + tr(" probe failed"), e);
                    }
                }
            }

            settingsRevision++;
            activateConfiguredPipeline();
            diagnostics.info(tr("Reload complete: ") + registrations.size() + tr(" pipelines, active ") + activePipelineId);
        } catch (RuntimeException | LinkageError e) {
            diagnostics.error(tr("Reload failed (loader remains usable)"), e);
        }
    }

    /**
     * Unloads every plugin instance the loader currently owns and releases what those instances
     * registered with the host, then clears the list. Instances released here are never called
     * again, so a later {@link #close()} cannot unload them a second time.
     *
     * <p>A plugin that throws from {@code onUnload} is reported and skipped; the remaining plugins
     * are still unloaded. A plugin whose {@code onLoad} failed is unloaded too, so whatever it
     * obtained before the failure gets a release call.
     *
     * <p>Pipeline instances are not unloaded here: pipeline teardown belongs to activation and
     * deactivation, and a reload closes the active pipeline before reaching this method.
     * @param reason recorded in diagnostics
     */
    private void unloadPlugins(String reason) {
        if (plugins.isEmpty()) {
            return;
        }
        int unloaded = 0;
        for (PipelinePlugin plugin : plugins) {
            String pluginId = pluginIdOf(plugin);
            HostServicesImpl host = hostServices.get(pluginId);
            try {
                plugin.onUnload();
                unloaded++;
            } catch (RuntimeException | LinkageError e) {
                if (diagnostics != null) {
                    diagnostics.error(tr("Plugin ") + pluginId + tr(" unload failed"), e);
                }
            } finally {
                // Invalidate the instance before releasing what it registered: a callback that runs
                // later through the old services object must not touch the new instance's state.
                String ownerPluginId = deactivateHost(host, pluginId);
                try {
                    releaseSceneContributions(ownerPluginId);
                } catch (RuntimeException e) {
                    // Cleanup failure of one instance must not stop the remaining plugins.
                    if (diagnostics != null) {
                        diagnostics.error(tr("Failed to release scene contributions of plugin ") + ownerPluginId, e);
                    }
                }
            }
        }
        diagnostics.info(tr("Unloaded ") + unloaded + " / " + plugins.size() + tr(" plugins") + "（" + reason + "）");
        plugins.clear();
    }

    /**
     * Marks a plugin instance as no longer loaded: its services object stops accepting calls that
     * could change loader state. Returns the owner ID used by its registrations.
     */
    private String deactivateHost(HostServicesImpl host, String pluginId) {
        if (host == null) {
            return pluginId;
        }
        host.deactivate();
        return host.ownerPluginId();
    }

    /**
     * Rolls back an instance whose load failed, in both the initialization and the reload path.
     *
     * <p>A failed instance leaves the active set immediately, so it is never probed and its
     * half-initialized pipelines are never activated or selected. Everything it registered that can
     * be revoked is removed here: scene contributions, capability claims, and pipelines that are not
     * currently active. Resources it obtained are released through exactly one {@code onUnload}
     * call, even when that call throws; because the instance left the list, a later {@link #close()}
     * cannot clean it up twice.
     * @param plugin failed instance
     * @param reason diagnostic detail describing where the failure occurred
     * @param cause recorded failure, possibly null
     */
    private void failPluginLoad(PipelinePlugin plugin, String reason, Throwable cause) {
        String pluginId = pluginIdOf(plugin);
        HostServicesImpl host = hostServices.get(pluginId);
        if (diagnostics != null) {
            diagnostics.error(tr("Plugin ") + pluginId + tr(" failed to load: ") + reason, cause);
        }
        plugins.remove(plugin);
        deactivatePipelineProvidedBy(host);
        String ownerPluginId = deactivateHost(host, pluginId);
        try {
            releaseSceneContributions(ownerPluginId);
        } catch (RuntimeException e) {
            if (diagnostics != null) {
                diagnostics.error(tr("Failed to release scene contributions of plugin ") + ownerPluginId, e);
            }
        }
        rollbackRegistrations(host);
        try {
            plugin.onUnload();
        } catch (RuntimeException | LinkageError e) {
            if (diagnostics != null) {
                diagnostics.error(tr("Plugin ") + pluginId + tr(" unload failed"), e);
            }
        }
        if (diagnostics != null) {
            diagnostics.info(tr("Cleaned up failed plugin ") + pluginId);
        }
    }

    /** Stops an active pipeline whose registration came from a failed instance. */
    private void deactivatePipelineProvidedBy(HostServicesImpl host) {
        if (host == null || activePipelineId == null) {
            return;
        }
        for (HostServicesImpl.Registration registration : host.registrations()) {
            if (registration.id().equals(activePipelineId)) {
                deactivatePipelineInternal(tr("Plugin load failed"));
                return;
            }
        }
    }

    /**
     * Removes a failed instance's non-contribution registrations. Pipelines that are not active are
     * dropped; capability claims are released by their owner, so a claim this instance overwrote
     * becomes effective again and a provider that is still valid is never revoked. A native library
     * path needs no removal because the lookup is driven by the plugin's bootstrap.
     */
    private void rollbackRegistrations(HostServicesImpl host) {
        if (host == null) {
            return;
        }
        for (HostServicesImpl.Registration registration : host.registrations()) {
            if (registration.id().equals(activePipelineId)) {
                continue;
            }
            registrations.remove(registration.id());
            pipelineStates.remove(registration.id());
        }
        releaseFailedPluginCapabilityClaims(host.ownerPluginId());
    }

    /**
     * Releases the capability claims owned by one plugin ID and records what changed. Exposed for the
     * failure path and for tests that must verify rollback isolation without provoking a second load
     * failure.
     * @param ownerPluginId claiming plugin ID
     * @return capability IDs whose claims were released
     */
    java.util.List<String> releaseFailedPluginCapabilityClaims(String ownerPluginId) {
        if (capabilities == null || ownerPluginId == null || ownerPluginId.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> released = capabilities.releaseClaims(ownerPluginId);
        if (diagnostics != null && !released.isEmpty()) {
            for (String capabilityId : released) {
                // Report the effective provider afterwards, so a capability that stays usable through
                // another provider is not mistaken for a failed dependency.
                diagnostics.info(tr("Claim of capability ") + capabilityId
                        + tr(" released by the failed plugin; effective provider now ")
                        + capabilities.find(capabilityId)
                        .map(descriptor -> descriptor.provider() + " / " + descriptor.level())
                        .orElse(tr("UNSUPPORTED (no valid provider)")));
            }
        }
        return released;
    }

    /** Remaining capability claims, capability ID to claiming owner IDs, for diagnostics and tests. */
    java.util.Map<String, java.util.List<String>> capabilityClaims() {
        return capabilities == null ? java.util.Map.of() : capabilities.claimsByCapability();
    }

    /** Removes the scene contributions owned by an unloaded plugin. */
    private void releaseSceneContributions(String ownerPluginId) {
        String owner = ownerPluginId == null ? "" : ownerPluginId;
        sceneContributors.entrySet().removeIf(entry -> {
            boolean owned = entry.getValue().ownerPluginId().equals(owner);
            if (owned) {
                brokenSceneContributors.remove(entry.getKey());
            }
            return owned;
        });
    }

    @Override
    public void requestReload(String reason) {
        reloadRequested.set(true);
        reloadReason = reason == null ? "" : reason;
    }

    @Override
    public void requestPipelineSwitch(GpuId id, String reason) {
        pendingSwitch = id;
        pendingSwitchReason = reason == null ? "" : reason;
    }

    /**
     * Processes pending requests before frame rendering. Defer switches requested during encoding until
     * this safe boundary.
     * @return whether reload or switching occurred
     */
    public boolean processPendingActions() {
        if (!initialized.get()) {
            return false;
        }
        if (reloadRequested.compareAndSet(true, false)) {
            reload(reloadReason);
            return true;
        }
        if (pendingSwitch != null) {
            GpuId target = pendingSwitch;
            String reason = pendingSwitchReason;
            pendingSwitch = null;
            pendingSwitchReason = "";
            activatePipeline(target, reason);
            return true;
        }
        return false;
    }

    // RenderDriver queries.

    @Override
    public boolean isInitialized() {
        return initialized.get() && plugins != null;
    }

    @Override
    public Optional<GpuId> activePipelineId() {
        return Optional.ofNullable(activePipelineId);
    }

    @Override
    public Optional<GpuId> configuredPipelineId() {
        if (config == null) {
            return activePipelineId();
        }
        ConfigView root = config.root();
        if (root.getBoolean(LoaderConfig.AUTO_ACTIVATE, true)) {
            return activePipelineId();
        }
        return Optional.ofNullable(GpuId.tryParse(
                root.getString(LoaderConfig.ACTIVE_PIPELINE, "")));
    }

    /** The explicit choice made by the active plugin. No pipeline means vanilla. */
    public boolean activePipelineNeedsHostWorldFrame() {
        RenderPipeline pipeline = activePipeline;
        if (pipeline == null) {
            return true;
        }
        try {
            return pipeline.hostSceneMode() == HostSceneMode.COMPATIBILITY;
        } catch (RuntimeException | LinkageError e) {
            diagnostics.warn(tr("Failed to read host drawing policy; preserving vanilla world rendering: ") + e);
            return true;
        }
    }

    /** The plan is read before Minecraft schedules its world draw passes. */
    public dev.luxloader.api.pipeline.WorldFramePlan activeWorldFramePlan() {
        RenderPipeline pipeline = activePipeline;
        return pipeline == null ? null : pipeline.worldFramePlan(currentScene,
                hostAdapter == null ? null : hostAdapter.sceneGeometryFeed(),
                device == null ? null : device.commands());
    }

    /** Fail visibly when the Minecraft world-frame hook never executes. */
    public void failActiveWorldFrame(String reason) {
        GpuId id = activePipelineId;
        if (id == null) return;
        diagnostics.error(tr("Pipeline ") + id + tr(" cannot attach to the world frame: ") + reason);
        failPipelineAfterCurrentFrame(id, PipelineInfo.State.FAILED,
                tr("World frame hook unavailable"), reason);
    }

    @Override
    public ConfigStore config() {
        return config;
    }

    @Override
    public CapabilityRegistry capabilities() {
        return capabilities;
    }

    @Override
    public Diagnostics diagnostics() {
        return diagnostics;
    }

    @Override
    public List<PipelineInfo> pipelines() {
        return List.copyOf(pipelineStates.values());
    }

    @Override
    public List<String> loadedPlugins() {
        return plugins.stream().map(p -> p.mod().id().toString()).toList();
    }

    @Override
    public Requirements.Verdict verify(PipelineDescriptor descriptor) {
        if (descriptor == null) {
            return Requirements.Verdict.fail(tr("Descriptor is null"));
        }
        GpuCapabilities caps = device == null ? null : device.capabilities();
        if (caps == null) {
            return Requirements.Verdict.fail(tr("Device not ready; validation unavailable"));
        }
        return descriptor.requirements().verify(caps, capabilities,
                nativeBridge != null && nativeBridge.isAvailable());
    }

    @Override
    public void close() {
        if (!initialized.compareAndSet(true, false)) {
            return;
        }
        try {
            // Guard both the cleanup decision and destruction with teardownLock. A flag alone permits shutdown and host-close threads to free the same objects concurrently while one is waiting for GPU idle.
            synchronized (teardownLock) {
                if (gpuTeardownDone) {
                    // Another thread already released GPU objects; avoid double destruction.
                    if (diagnostics != null) {
                        diagnostics.info(tr("Local Vulkan objects already released; skipping duplicate release"));
                    }
                } else if (hostDeviceAlreadyDestroyed()) {
                    gpuTeardownDone = true;
                    // Check host destruction before any pipeline teardown, not merely before device.close: teardown itself calls waitIdle. Calling wait/destroy on an already destroyed borrowed device caused a native access violation. Drop references instead on this late-shutdown path.
                    if (diagnostics != null) {
                        diagnostics.warn(tr("[HOST] Host device was destroyed without the release hook; ")
                                + tr("skipping all local Vulkan releases to avoid accessing destroyed device handles"));
                    }
                    abandonGpuObjects();
                } else {
                    // Claim cleanup before releasing so a later host teardown callback returns immediately.
                    gpuTeardownDone = true;
                    deactivatePipelineInternal(tr("Close loader"));
                }
            }
            // Unload plugins after GPU teardown and before the device/class loaders go away, so
            // plugin-level resources still see a usable environment. Instances already released by
            // a reload are gone from the list and cannot be unloaded twice.
            unloadPlugins(tr("Close loader"));
            if (config != null) {
                config.save();
            }
            if (device != null && !hostDeviceReleased) {
                if (device.isOwned()) {
                    device.close();
                } else {
                    // Attached host device and the release hook never fired
                    // (mixin target renamed; require=0 is silent). Never touch a
                    // device the host may already have destroyed -- leaking a few
                    // handles is strictly better than calling into a dead device.
                    diagnostics.warn("[HOST] release hook did not fire; skipping local Vulkan teardown");
                }
            }
            device = null;
            // Close archive loaders last, after plugins and pipelines no longer need lazy class/resource access, releasing Windows file locks. Initialization may have failed before the loader existed.
            if (pluginLoader != null) {
                pluginLoader.close();
            }
            if (hostProbe != null) {
                if (hostProbe.ownsInstance()) VulkanApi.destroyInstance(hostProbe.instance());
                hostProbe = null;
            }
            // Save the final capability/profile state after device/plugin cleanup; persistence failure only warns.
            if (deviceProfiles != null) {
                syncDeviceProfile();
                if (!deviceProfiles.save() && diagnostics != null) {
                    for (String warning : deviceProfiles.warnings()) {
                        diagnostics.warn(warning);
                    }
                }
            }
            if (diagnostics != null) {
                diagnostics.info(tr("LuxLoader closed"));
            }
        } catch (RuntimeException e) {
            if (diagnostics != null) {
                diagnostics.error(tr("Loader shutdown failed"), e);
            }
        } finally {
            // Always close the heartbeat handle so Windows permits truncation on the next run.
            closeHeartbeat();
        }
    }

    private void teardownQuietly() {
        try {
            teardownActivePipeline();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup must not interrupt the game.
        }
    }

    // ==================================================================
    // HostServicesImpl.RuntimeContext
    // ==================================================================

    @Override
    public void onPipelineRegistered(HostServicesImpl.Registration registration) {
        registrations.put(registration.id(), registration);
        pipelineStates.put(registration.id(), new PipelineInfo(
                registration.id(), registration.descriptor(), registration.providerPlugin(),
                PipelineInfo.State.REGISTERED, tr("Registered, waiting for activation"), 0L));

    }

    /** Validate only after every plugin has had a chance to publish probe results. */
    private void refreshPipelineAvailability() {
        if (device == null) return;
        for (var registration : registrations.values()) {
            var info = pipelineStates.get(registration.id());
            if (info.state() != PipelineInfo.State.REGISTERED
                    && info.state() != PipelineInfo.State.REJECTED) continue;
            var verdict = verify(registration.descriptor());
            updateState(registration.id(), verdict.passed()
                    ? PipelineInfo.State.REGISTERED : PipelineInfo.State.REJECTED,
                    verdict.passed() ? tr("Ready, waiting for activation") : verdict.explain());
        }
    }

    @Override
    public void onReloadRequested(String reason) {
        requestReload(reason);
    }

    @Override
    public void onPipelineSwitchRequested(GpuId id, String reason) {
        requestPipelineSwitch(id, reason);
    }

    @Override
    public Optional<GpuId> currentPipeline() {
        return activePipelineId();
    }

    @Override
    public String loaderVersion() {
        return VERSION;
    }

    @Override
    public ConfigSchema schemaFor(String pluginId) {
        HostServicesImpl host = hostServices.get(pluginId);
        return host == null ? ConfigSchema.builder().build() : host.declareConfig();
    }

    @Override
    public PipelineSettings settingsFor(String pluginId, ConfigSchema schema) {
        ConfigView view = config.section("plugins." + encodePluginId(pluginId));
        return new PipelineSettingsImpl(view, schema, "file", settingsRevision);
    }

    // Frame driving for adapters.

    /** Active pipeline, possibly null. */
    public RenderPipeline activePipeline() {
        return activePipeline;
    }

    /** Device, possibly null. */
    public GpuDevice gpuDevice() {
        return device;
    }

    /** Pipeline-scoped resources. */
    public VulkanResourceProvider pipelineResources() {
        return pipelineResources;
    }

    /**
     * Refreshes currentSetup from actual host dimensions/formats and advances the frame index. The old
     * fixed test setup rendered a real 854x480 input as 1920x1080 and kept resetting history at index
     * zero. Compare dimensions each frame when no reliable resize signal exists; reset only on actual
     * changes or first synchronization.
     */
    private void syncSetupFromHost(HostAdapter.HostFrameTextures textures) {
        // Missing first-frame textures have no valid dimensions; do not replace setup with a false 1x1 resize.
        if (textures == null || textures.displayWidth() <= 0 || textures.renderWidth() <= 0) {
            return;
        }
        int displayWidth = textures.displayWidth();
        int displayHeight = textures.displayHeight();
        int renderWidth = textures.renderWidth();
        int renderHeight = textures.renderHeight();
        // Map native VkFormat to the API format. Preserve the previous format if the value is unmapped rather than replacing it with UNDEFINED.
        dev.luxloader.api.gpu.GpuFormat hostFormat =
                dev.luxloader.api.gpu.GpuFormat.byVkFormat(textures.colorFormat());
        dev.luxloader.api.gpu.GpuFormat colorFormat =
                hostFormat == dev.luxloader.api.gpu.GpuFormat.UNDEFINED
                        ? currentSetup.colorFormat() : hostFormat;
        dev.luxloader.api.gpu.GpuFormat hostDepthFormat =
                dev.luxloader.api.gpu.GpuFormat.byVkFormat(textures.depthFormat());
        dev.luxloader.api.gpu.GpuFormat depthFormat =
                hostDepthFormat == dev.luxloader.api.gpu.GpuFormat.UNDEFINED
                        ? currentSetup.depthFormat() : hostDepthFormat;

        boolean firstSync = !hostSetupSynced;
        boolean changed = displayWidth != currentSetup.displayWidth()
                || displayHeight != currentSetup.displayHeight()
                || renderWidth != currentSetup.renderWidth()
                || renderHeight != currentSetup.renderHeight()
                || colorFormat != currentSetup.colorFormat()
                || depthFormat != currentSetup.depthFormat();

        if (!firstSync && !changed) {
            // Advance the frame and clear resetRequested in steady state; copying the immutable record otherwise carries a one-frame reset forever.
            currentSetup = currentSetup.withFrameIndex(currentSetup.frameIndex() + 1)
                    .withReset(false);
            return;
        }

        hostSetupSynced = true;
        FrameSetup synced = new FrameSetup(displayWidth, displayHeight, renderWidth, renderHeight,
                0f, // Let the record derive nonpositive scale from render/display dimensions in one place.
                colorFormat, depthFormat, currentSetup.hdr(),
                currentSetup.sampleCount(),
                firstSync ? currentSetup.frameIndex() : currentSetup.frameIndex() + 1,
                currentSetup.timing(),
                true, // Reset history after the first synchronization or a size/format change.
                currentSetup.uiSeparated(), currentSetup.hudHidden(),
                currentSetup.pauseScreenOpen(), currentSetup.worldLoaded());
        updateFrameSetup(synced);
    }

    /** Updates frame dimensions/formats from the adapter. */
    public void updateFrameSetup(FrameSetup setup) {
        if (setup == null) {
            return;
        }
        boolean renderTargetChanged = setup.displayWidth() != currentSetup.displayWidth()
                || setup.displayHeight() != currentSetup.displayHeight()
                || setup.renderWidth() != currentSetup.renderWidth()
                || setup.renderHeight() != currentSetup.renderHeight()
                || setup.colorFormat() != currentSetup.colorFormat()
                || setup.depthFormat() != currentSetup.depthFormat();
        this.currentSetup = setup;
        if (renderTargetChanged) {
            previousFrameCamera = null;
        }
        if (renderTargetChanged && activePipeline != null) {
            diagnostics.info(tr("Render target size or format changed: ") + setup.describe());
            readaptPipelineToCurrentSetup(tr("Rebuild after render target change"));
        }
    }

    /**
     * Readapts the pipeline to current dimensions after an explicit resource-rebuild request. Sizes need
     * not change, so passing the identical setup to change detection would incorrectly do nothing.
     */
    private void readaptPipelineToCurrentSetup(String reason) {
        if (activePipeline == null) {
            return;
        }
        // Recreate the pipeline if it cannot adapt itself.
        boolean handled = false;
        try {
            handled = activePipeline.resize(currentSetup);
        } catch (RuntimeException e) {
            diagnostics.error(tr("Pipeline resize threw; loader will rebuild it"), e);
        }
        if (handled) {
            return;
        }
        GpuId id = activePipelineId;
        diagnostics.info(tr("Pipeline did not adapt (") + reason + tr("); loader will rebuild it"));
        if (id != null) {
            teardownActivePipeline();
            updateState(id, PipelineInfo.State.CLOSED, reason);
            activatePipeline(id, reason);
        }
    }

    /** Current frame setup. */
    public FrameSetup frameSetup() {
        return currentSetup;
    }

    /** Reports a frame rendering exception. */
    public void reportFrameFailure(String what, Throwable cause) {
        consecutiveFrameFailures++;
        diagnostics.error(tr("Frame rendering failed (consecutive failure ") + consecutiveFrameFailures + tr("): ") + what, cause);
        if (consecutiveFrameFailures >= 5) {
            GpuId id = activePipelineId;
            diagnostics.error(tr("Pipeline ") + id + tr(" failed consecutively ") + consecutiveFrameFailures
                    + tr(" times; automatically disabled to prevent repeated frame corruption"));
            failPipelineAfterCurrentFrame(id, PipelineInfo.State.DISABLED,
                    tr("Consecutive rendering failures"), tr("Automatically disabled after ") + consecutiveFrameFailures + tr(" consecutive failed frames; ")
                            + DiagnosticsImpl.stackTrace(cause));
            consecutiveFrameFailures = 0;
        }
    }

    /** Resets the failure count after a successful frame. */
    public void reportFrameSuccess() {
        consecutiveFrameFailures = 0;
    }

    /** Generates and exports diagnostics. */
    public Path exportDiagnostics() {
        if (diagnostics == null) {
            throw new IllegalStateException(tr("Loader is not initialized"));
        }
        diagnostics.fact("loader.exportedAt", java.time.LocalDateTime.now().toString());
        // Read by verification criteria: false means the release hook never fired, so any
        // "no leaked objects" result would be a false negative.
        diagnostics.fact("host.releaseHookFired", Boolean.toString(hostReleaseHookFired));
        // Save profiles during export because shutdown may leave no later opportunity.
        syncDeviceProfile();
        if (deviceProfiles != null) {
            deviceProfiles.save();
        }
        return diagnostics.exportReportToFile();
    }

    /** Diagnostic channel for adapter hooks to retain direct observations about host invocation in exported reports. */
    public void logDiagnostic(String message) {
        if (diagnostics != null && message != null) {
            diagnostics.info(message);
        }
    }

    /** Set once the host is about to destroy the VkDevice we are attached to. */
    private volatile boolean hostDeviceReleased;

    /**
     * Serializes GPU cleanup between the render-thread host-close hook and JVM shutdown thread. A volatile
     * flag set before work finishes cannot prevent double destruction. The lock fixes that provable race;
     * it does not by itself establish the cause of a previously observed native heap fail-fast crash.
     */
    private final Object teardownLock = new Object();

    /** Whether a thread has released local GPU objects, guarded by teardownLock. */
    private boolean gpuTeardownDone;

    /**
     * Whether the injected host hook actually fired.
     *
     * <p>The mixin uses {@code require = 0}, so a renamed target degrades silently: the
     * release simply never happens and nothing fails loudly. Recording it as a report fact
     * turns "the hook probably ran" into something a verification criterion can read.
     */
    private volatile boolean hostReleaseHookFired;

    /**
     * Release every Vulkan object of ours while the device is still valid.
     *
     * <p>Called from the hook injected at {@code VulkanDevice#close} HEAD. That is the
     * only point where the device is guaranteed alive: the JVM shutdown hook runs
     * <em>after</em> the host already called {@code vkDestroyDevice}, and touching the
     * device there is what produced VUID-vkDestroyDevice-device-05137 together with a
     * 0xC0000409 exit.
     *
     * <p>Idempotent -- both the host hook and the loader close path may call it.
     */
    public void onHostDeviceClosing() {
        // Serialize both the guard and cleanup itself; guarding only the flag still permits concurrent destruction.
        synchronized (teardownLock) {
            if (gpuTeardownDone || hostDeviceReleased || device == null) {
                return;
            }
            gpuTeardownDone = true;
            hostDeviceReleased = true;
            hostReleaseHookFired = true;
            try {
                device.waitIdleAll();
                if (device.commands() instanceof dev.luxloader.core.vulkan.VulkanCommands commands) {
                    commands.retireHostFramesAfterIdle();
                }
                deactivatePipelineInternal("host device closing");
                // The adapter owns the present texture. It has to go before the device,
                // otherwise it shows up as a leaked object at vkDestroyDevice.
                if (hostAdapter != null) {
                    hostAdapter.releaseHostResources();
                }
                device.close();
                if (diagnostics != null) {
                    diagnostics.info("[HOST] device closing: local Vulkan objects released");
                }
            } catch (RuntimeException e) {
                if (diagnostics != null) {
                    diagnostics.warn("[HOST] releasing local Vulkan objects failed: " + e);
                }
            }
        }
    }

    /** Game version, empty until supplied by the adapter. */
    public String gameVersion() {
        String version = System.getProperty("luxloader.gameVersion", "");
        return version == null ? "" : version;
    }

    /** Sets the game version before initialization. */
    public void setGameVersion(String version) {
        if (version != null && !version.isBlank()) {
            System.setProperty("luxloader.gameVersion", version);
        }
    }

    /** Registered pipeline count. */
    public int registeredPipelineCount() {
        return registrations.size();
    }

    /**
     * Frame-boundary callback for descriptor reclamation, queued readbacks and statistics. Never reclaim
     * mid-frame while commands may still reference resources.
     */
    public void onFrameFinished() {
        if (device != null) {
            // Reclaim frame descriptor sets by resetting their pool.
            if (device.commands() instanceof dev.luxloader.core.vulkan.VulkanCommands commands) {
                commands.resetDescriptorPools();
            }
            // Process queued asynchronous readbacks.
            device.flushReadbacks();
        }
        reportFrameSuccess();
    }

    // Host adapter integration.

    /**
     * Wraps host handles from HostDevice without transferring ownership; the host remains responsible for
     * device destruction.
     */
    @Override
    public boolean attachDevice(HostAdapter.HostDevice hostDevice) {
        if (hostDevice == null || !hostDevice.isUsable()) {
            diagnostics.error(tr("Device attachment failed: invalid handle (")
                    + (hostDevice == null ? "null" : hostDevice.describe()) + "）");
            return false;
        }
        if (device != null) {
            diagnostics.warn(tr("Device already attached; ignoring this request"));
            return false;
        }
        // Prefer adapter-observed capabilities, then physical-device enumeration from the instance, then a conservative existence-only snapshot. The host may not retain a VkPhysicalDevice handle, so its absence cannot alone prohibit attachment.
        ResolvedHostDevice resolved = resolveHostDevice(hostDevice);
        if (resolved.capabilities() == null) {
            diagnostics.error(tr("Device attachment failed: cannot read capabilities"));
            return false;
        }
        diagnostics.info(tr("Attaching host device: ") + hostDevice.describe()
                + (resolved.physicalDevice() != hostDevice.physicalDevice()
                ? tr(" (physical device handle enumerated from the instance: 0x")
                + Long.toHexString(resolved.physicalDevice()) + "）" : ""));
        // The resolved physical device was verified against the actual host instance.
        return attachDevice(hostDevice.device(), resolved.physicalDevice(), resolved.capabilities());
    }

    /** Resolved host capabilities and usable physical-device handle. */
    private record ResolvedHostDevice(GpuCapabilities capabilities, long physicalDevice) {
    }

    /** Validates and borrows the host instance/physical-device chain without creating a substitute instance. */
    private ResolvedHostDevice resolveHostDevice(HostAdapter.HostDevice hostDevice) {
        try {
            VulkanApi.Probe probe = VulkanApi.borrowHostDevice(hostDevice.instance(),
                    hostDevice.physicalDevice(), hostDevice.instanceApiVersion(), hostDevice.instanceExtensions());
            if (hostProbe != null && hostProbe.ownsInstance()) VulkanApi.destroyInstance(hostProbe.instance());
            hostProbe = probe;
            diagnostics.info(tr("Using the host's complete Vulkan device chain: ") + hostDevice.describe()
                    + "，instance API " + VulkanApi.formatVersion(hostDevice.instanceApiVersion()));
            return new ResolvedHostDevice(enabledHostCapabilities(probe.capabilities()),
                    probe.physicalDevice().address());
        } catch (RuntimeException | LinkageError failure) {
            diagnostics.error(tr("Cannot attach the host Vulkan device chain; refusing a physical device from another instance"), failure);
            return new ResolvedHostDevice(null, 0L);
        }
    }

    private GpuCapabilities enabledHostCapabilities(GpuCapabilities physical) {
        if (hostAdapter == null) return physical;
        java.util.Optional<java.util.Set<String>> enabled;
        try {
            enabled = hostAdapter.enabledDeviceExtensions();
        } catch (RuntimeException e) {
            diagnostics.warn(tr("Host enabled-extension query failed: ") + e);
            return physical;
        }
        if (enabled == null || enabled.isEmpty()) return physical;
        java.util.Set<String> actual = enabled.get();
        diagnostics.info(tr("Host device enabled ") + actual.size() + tr(" extensions (physical device supports ")
                + physical.extensions().size() + tr("RenderDriverImpl.624bee9cbd", ")"));
        return new GpuCapabilities(physical.deviceName(), physical.vendor(),
                physical.vendorId(), physical.deviceId(), physical.driverVersion(),
                physical.driverName(), physical.apiVersion(), actual,
                physical.instanceExtensions(), physical.maxComputeWorkGroupInvocations(),
                physical.maxComputeSharedMemorySize(), physical.timestampPeriod(),
                physical.deviceMemoryBytes(), physical.maxImageDimension2D());
    }
    /** Conservative fallback capabilities when only device existence is known. */
    private GpuCapabilities minimalCapabilities() {
        return new GpuCapabilities(tr("Unknown device (host did not provide a capability snapshot)"),
                dev.luxloader.api.gpu.GpuVendor.UNKNOWN,
                0, 0, 0, "", 0, java.util.Set.of(), java.util.Set.of(), 0, 0, 0f, 0L, 0);
    }

    /**
     * Installs the single host adapter supplying devices, frame textures and hooks. Replacing it
     * mid-session would leave initialized pipelines using old devices; restart the loader to change hosts.
     */
    @Override
    public boolean attachHostAdapter(HostAdapter adapter) {
        if (adapter == null) {
            return false;
        }
        if (hostAdapter != null && hostAdapter != adapter) {
            diagnostics.warn(tr("Host adapter already installed (") + hostAdapter.hostName() + tr("); ignoring replacement request"));
            return false;
        }
        hostAdapter = adapter;
        // Include adapter notes so failed integration paths retain their causes in diagnostics.
        diagnostics.section("Host adapter", () -> {
            StringBuilder sb = new StringBuilder();
            sb.append("  ").append(adapter.hostName()).append(" / ").append(adapter.backendName());
            sb.append(System.lineSeparator());
            sb.append(tr("  Current presentation target: "))
                    .append(currentPresentImage.isUsable() ? currentPresentImage.describe() : tr("Unavailable"));
            String why = adapter.presentImageNote();
            if (why != null && !why.isBlank()) {
                sb.append("（").append(why).append("）");
            }
            // Include all adapter resolution notes, not just the latest presentation failure, so failed reflection hops remain diagnosable.
            for (String note : adapter.notes()) {
                if (note != null && !note.isBlank()) {
                    sb.append(System.lineSeparator()).append("  · ").append(note);
                }
            }
            return sb.toString();
        });
        diagnostics.info(tr("Installing host adapter: ") + adapter.hostName()
                + tr(", backend=") + adapter.backendName()
                + (adapter.supportsSceneExtraction() ? tr(", scene extraction supported") : tr(", scene extraction unsupported")));

        // Merge adapter capabilities before plugin onLoad decisions.
        try {
            adapter.registerCapabilities(this::registerCapability);
        } catch (RuntimeException e) {
            diagnostics.warn(tr("Adapter capability registration failed (continuing startup): ") + e);
        }
        return true;
    }

    /** Installed adapter, or null. */
    public HostAdapter hostAdapter() {
        return hostAdapter;
    }

    @Override public dev.luxloader.api.scene.ResourceAccess resources() {
        return hostAdapter == null ? dev.luxloader.api.scene.ResourceAccess.EMPTY : hostAdapter.resources();
    }

    /**
     * Fetches host textures, returning EMPTY if the adapter is absent or fails so pipelines can fall back
     * without losing the frame.
     */
    public HostAdapter.HostFrameTextures hostFrameTextures() {
        if (hostAdapter == null) {
            return HostAdapter.HostFrameTextures.EMPTY;
        }
        try {
            HostAdapter.HostFrameTextures textures = hostAdapter.frameTextures();
            return textures == null ? HostAdapter.HostFrameTextures.EMPTY : textures;
        } catch (RuntimeException e) {
            diagnostics.warn(tr("Failed to acquire host frame textures: ") + e);
            return HostAdapter.HostFrameTextures.EMPTY;
        }
    }

    @Override
    public boolean isDeviceReady() {
        return device != null;
    }

    /** Processes frame-boundary requests and captures scene state for exclusive pipelines before rendering. */
    @Override
    public void onFrameBegin() {
        if (!initialized.get()) {
            return;
        }
        try {
            processPendingActions();
            if (hostAdapter != null && activePipeline != null) {
                long revision = hostAdapter.resources().revision();
                if (revision != sceneResourceRevision) {
                    if (pipelineResources != null && sceneResourceRevision != Long.MIN_VALUE) {
                        device.commands().waitIdleAll(); pipelineResources.invalidateBorrowedImages();
                    }
                    sceneResourceRevision = revision;
                }
                SceneSnapshot snapshot = activePipelineNeedsHostWorldFrame()
                        && (!activePipeline.requiresSceneSnapshot()
                        || hostAdapter.sceneGeometryFeed() != null)
                        ? hostAdapter.sceneMetadata() : hostAdapter.sceneSnapshot();
                if (snapshot != null && snapshot.worldLoaded() && activePipeline.requiresDynamicGeometry()) {
                    var dynamic = mergeSceneContributions(snapshot, snapshot.dynamicMeshes());
                    var images = new java.util.ArrayList<>(snapshot.images());
                    for (var mesh : dynamic) {
                        if (!images.contains(mesh.albedo())) images.add(mesh.albedo());
                    }
                    snapshot = new SceneSnapshot(snapshot.camera(), snapshot.chunks(), snapshot.environment(),
                            snapshot.textures(), snapshot.frameIndex(), snapshot.worldLoaded(), snapshot.hudHidden(),
                            snapshot.totalVertices(), snapshot.totalIndices(), snapshot.cpuMeshes(), snapshot.materials(),
                            images, snapshot.compiledMeshes(), snapshot.geometryRevision(), snapshot.atlasSprites(), dynamic);
                }
                this.currentScene = snapshot;
                if (snapshot != null && currentSetup.worldLoaded() != snapshot.worldLoaded()) {
                    // Update worldLoaded from actual host state each frame rather than retaining the test setup's constant true value.
                    updateFrameSetup(currentSetup.withWorldLoaded(snapshot.worldLoaded()));
                    diagnostics.info(tr("World state changed: ") + (snapshot.worldLoaded()
                            ? tr("World loaded") : tr("Outside a world")) + "（" + snapshot.describe() + "）");
                }
                if (snapshot != null && diagnostics.isVerbose()) {
                    diagnostics.debug(tr("Scene snapshot: ") + snapshot.describe());
                }
            }
        } catch (RuntimeException e) {
            reportFrameFailure(tr("Frame begin processing"), e);
        }
    }

    /** Registered scene contributor IDs; empty before any plugin registers one. */
    java.util.List<GpuId> sceneContributorIds() {
        return java.util.List.copyOf(sceneContributors.keySet());
    }

    /**
     * Invokes the registered scene contributors and appends their meshes to the host's dynamic
     * geometry. Contributor failures, including a null result, are reported once per contributor and
     * skipped: one misbehaving plugin must not remove the whole frame's geometry or stop the other
     * contributors.
     *
     * <p>Changes to the contributor registry take effect at the next pass boundary. Each pass runs
     * the contributions captured when the pass started, so a contributor that releases itself, or is
     * released by an earlier contributor, still runs exactly once in that pass; contributors
     * registered during the merge run in the following pass of the same merge. The rebuild performs
     * at most {@value #MAX_CONTRIBUTOR_PASSES} passes and reports remaining churn. Registration,
     * release and the merge itself must be called from the same thread (the render thread or an
     * equivalent single-threaded boundary); the tables are not synchronized.
     * @param hostScene scene handed to contributors
     * @param hostDynamicMeshes host's own dynamic meshes, kept first
     * @return host meshes followed by contributed meshes
     */
    java.util.List<dev.luxloader.api.scene.DynamicSceneMesh> mergeSceneContributions(
            SceneSnapshot hostScene,
            java.util.List<dev.luxloader.api.scene.DynamicSceneMesh> hostDynamicMeshes) {
        java.util.List<dev.luxloader.api.scene.DynamicSceneMesh> merged =
                new java.util.ArrayList<>(hostDynamicMeshes);
        if (sceneContributors.isEmpty()) {
            return merged;
        }
        // Capture the pass before running any callback: a contributor that releases itself, or is
        // released by an earlier contributor, still runs exactly once in the pass it was registered
        // for. Releases and additions take effect at the next pass boundary.
        java.util.List<java.util.Map.Entry<GpuId, SceneContribution>> pass =
                new java.util.ArrayList<>(sceneContributors.entrySet());
        java.util.Set<GpuId> ranInThisMerge = new java.util.LinkedHashSet<>();
        int passes = 0;
        while (!pass.isEmpty()) {
            if (passes++ >= MAX_CONTRIBUTOR_PASSES) {
                if (diagnostics != null) {
                    diagnostics.warn(tr("Scene contributors keep adding or removing registrations during the merge; ")
                            + tr("the remaining new contributors start on the next frame"));
                }
                break;
            }
            for (var entry : pass) {
                GpuId id = entry.getKey();
                ranInThisMerge.add(id);
                SceneContribution contribution = entry.getValue();
                java.util.List<dev.luxloader.api.scene.DynamicSceneMesh> contributed = null;
                try {
                    contributed = contribution.contributor().contribute(hostScene);
                } catch (RuntimeException | LinkageError e) {
                    reportSceneContributionFailure(id, contribution.ownerPluginId(), e);
                }
                if (contributed == null) {
                    if (!brokenSceneContributors.contains(id)) {
                        reportSceneContributionFailure(id, contribution.ownerPluginId(),
                                new IllegalStateException("Scene contributor returned null"));
                    }
                    continue;
                }
                for (var mesh : contributed) {
                    if (mesh != null) {
                        merged.add(mesh);
                    }
                }
            }
            // Contributors registered during this merge run in the following pass of the same merge,
            // so a mesh list built here does not silently lose geometry until the next frame.
            java.util.List<java.util.Map.Entry<GpuId, SceneContribution>> added =
                    new java.util.ArrayList<>();
            for (var entry : sceneContributors.entrySet()) {
                if (!ranInThisMerge.contains(entry.getKey())) {
                    added.add(entry);
                }
            }
            pass = added;
        }
        return merged;
    }

    /** Upper bound on contributor rebuild passes within one merge, to bound pathological churn. */
    private static final int MAX_CONTRIBUTOR_PASSES = 2;

    /** Reports a contributor failure once, so a broken contributor cannot flood the log every frame. */
    private void reportSceneContributionFailure(GpuId id, String ownerPluginId, Throwable cause) {
        if (!brokenSceneContributors.add(id)) {
            return;
        }
        if (diagnostics != null) {
            diagnostics.warn(tr("Scene contribution from plugin ") + ownerPluginId + " (" + id
                    + tr(") failed; skipping this contributor while the rest of the frame continues: ")
                    + DiagnosticsImpl.stackTrace(cause));
        }
    }

    /**
     * Executes the pipeline before presentation in two phases: setup/encodeFrame declare passes, then
     * compilation establishes ordering and each pass records commands. Submit centrally after recording to
     * retain control over queue grouping and barriers.
     */
    private java.util.function.Supplier<org.lwjgl.vulkan.VkCommandBuffer> hostFrameAllocate;
    private java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> hostFrameExecute;
    private record DeferredHostFailure(GpuId id, PipelineInfo.State state,
                                       String reason, String detail) { }
    private final java.util.Queue<PipelineFailure> runtimeFailures = new java.util.concurrent.ConcurrentLinkedQueue<>();

    @Override
    public Optional<PipelineFailure> pollPipelineFailure() {
        return Optional.ofNullable(runtimeFailures.poll());
    }

    private DeferredHostFailure deferredHostFailure;

    private void failPipelineAfterCurrentFrame(GpuId id, PipelineInfo.State state,
                                               String reason, String detail) {
        if (hostFrameAllocate != null) {
            // Minecraft has not submitted this frame yet. Earlier pass buffers may
            // still refer to pipeline-owned descriptors, images and shader modules.
            deferredHostFailure = new DeferredHostFailure(id, state, reason, detail);
            return;
        }
        deactivatePipelineInternal(reason);
        if (id != null && state != null) updateState(id, state, detail);
        clearConfiguredPipeline(detail);
        if (id != null) runtimeFailures.add(new PipelineFailure(id, reason, detail));
    }

    /** Record draw and output passes in the host submission, after setup work. */
    public PresentOutcome onPreparedWorldFrame(HostAdapter.HostFrameTextures textures,
            java.util.function.Supplier<org.lwjgl.vulkan.VkCommandBuffer> allocate,
            java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> execute) {
        if (hostFrameAllocate != null) {
            throw new IllegalStateException("Nested prepared world frame");
        }
        hostFrameAllocate = java.util.Objects.requireNonNull(allocate);
        hostFrameExecute = java.util.Objects.requireNonNull(execute);
        try {
            return onBeforePresent(textures);
        } finally {
            hostFrameAllocate = null;
            hostFrameExecute = null;
        }
    }

    private <T> T recordHostFrame(java.util.function.Supplier<T> work) {
        if (hostFrameAllocate == null) return work.get();
        if (!(device.commands() instanceof dev.luxloader.core.vulkan.VulkanCommands commands)) {
            throw new IllegalStateException("Host frame requires VulkanCommands");
        }
        return commands.withHostEncoder(hostFrameAllocate, hostFrameExecute, work);
    }

    @Override
    public PresentOutcome onBeforePresent(HostAdapter.HostFrameTextures textures) {
        if (!initialized.get() || activePipeline == null || textures == null) {
            return PresentOutcome.PASSTHROUGH;
        }
        try {
            // Synchronize actual dimensions/formats before constructing frame context or calling setupFrame; allocation and dispatch derive from them.
            syncSetupFromHost(textures);

            var frameTextures = new dev.luxloader.api.frame.FrameTextures(
                    textures.color(), textures.depth(), textures.motionVectors(),
                    textures.exposure(), null, null,
                    textures.swapchain(), textures.ui(), null);

            var timing = currentSetup.timing();
            // Forward the complete frame-boundary scene snapshot, not just its camera, so plugins can render extracted geometry.
            SceneSnapshot scene = currentScene == null
                    ? SceneSnapshot.empty(tracedFrames) : currentScene;
            CameraParams camera = scene.camera();
            boolean cameraCut = previousFrameCamera == null || !scene.worldLoaded()
                    || !camera.hasProjection() || !previousFrameCamera.hasProjection()
                    || currentSetup.resetRequested()
                    || !scene.environment().dimensionName().equals(previousFrameDimension)
                    || cameraJumped(camera, previousFrameCamera);
            var frame = new dev.luxloader.api.frame.FrameContext(
                    frameTextures, camera,
                    timing, device, device == null ? null : device.commands(),
                    computeQueue == null ? graphicsQueue : computeQueue,
                    textures.exposure() != null, currentSetup.pauseScreenOpen(),
                    currentSetup.worldLoaded(), textures.ui() != null, scene,
                    previousFrameCamera, cameraCut,
                    hostAdapter == null ? null : hostAdapter.sceneGeometryFeed());

            tracedFrames++;
            trace(tr("Begin #") + tracedFrames + tr(" (acquire presentation target first)"));
            refreshPresentImage();
            trace(tr("Presentation target acquired: ") + currentPresentImage.describe());
            FrameControl control = activePipeline.setupFrame(currentSetup, frame);
            trace(tr("setupFrame completed"));
            applyFrameControl(control);
            if (control != null && !control.run()) {
                diagnostics.debug(tr("Pipeline bypassed this frame: ") + control.describe());
                return PresentOutcome.PASSTHROUGH;
            }

            trace(tr("Frame graph started"));
            if (!recordHostFrame(() -> runFrameGraph(frame, textures))) {
                GpuId failed = activePipelineId;
                failPipelineAfterCurrentFrame(failed, PipelineInfo.State.FAILED,
                        tr("Frame graph failed"), lastFrameGraphFailure == null
                                ? tr("Frame graph execution failed") : lastFrameGraphFailure);
                return PresentOutcome.PASSTHROUGH;
            }
            trace(tr("Frame graph completed"));

            var target = new dev.luxloader.api.pipeline.PresentRequest.Target(
                    textures.swapchain(),
                    Math.max(1, textures.displayWidth()), Math.max(1, textures.displayHeight()),
                    currentSetup.colorFormat(), 0,
                    dev.luxloader.api.pipeline.PresentRequest.PresentMode.FIFO,
                    currentSetup.hdr(), currentSetup.timing().vsync());

            trace(tr("Presentation decision started"));
            var request = activePipeline.present(frame, target);
            var requested = request.request();
            trace(tr("Presentation decision completed: present=") + requested.present()
                    + " image=" + (requested.image() == null ? "null" : "0x" + Long.toHexString(requested.image().bits())));
            if (!requested.present()) {
                return PresentOutcome.PASSTHROUGH;
            }
            // Retain final output for next-frame history used by temporal upscaling, TAA and frame generation.
            if (requested.image() != null && !requested.image().isNull()) {
                if (!recordHostFrame(() -> blitToPresentTarget(requested.image()))) {
                    GpuId failed = activePipelineId;
                    failPipelineAfterCurrentFrame(failed, PipelineInfo.State.FAILED,
                            tr("Final image transfer failed"), tr("Pipeline ") + failed + tr(" cannot produce the final image"));
                    return PresentOutcome.PASSTHROUGH;
                }
                lastFrameOutput = requested.image();
            }
            return new PresentOutcome(
                    requested.image() != null && !requested.image().isNull(),
                    requested.image(),
                    requested.additionalFrames(),
                    requested.pacing(),
                    requested.targetIntervalNs(),
                    requested.note());
        } catch (RuntimeException e) {
            reportFrameFailure(tr("Presentation decision"), e);
            return PresentOutcome.PASSTHROUGH;
        }
    }

    private static boolean cameraJumped(CameraParams current, CameraParams previous) {
        float[] a = current.worldPosition();
        float[] b = previous.worldPosition();
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz > 16.0 * 16.0;
    }

    // Execution state exposed to diagnostics/tests.

    /** Previous compiled graph, or null before the first frame. */
    public FrameGraphImpl lastFrameGraph() {
        return lastFrameGraph;
    }

    /** Previous compilation result, or null before the first frame. */
    public FrameGraph.Compiled lastCompiled() {
        return lastCompiled;
    }

    /** Disabled failed passes: node ID to reason. */
    public Map<String, String> failedPasses() {
        return Map.copyOf(failedPasses);
    }

    /** Cumulative submitted command buffers. */
    public long submittedCommandBufferCount() {
        return submittedCommandBufferCount;
    }

    /** Cumulative requested history resets. */
    public long historyResetCount() {
        return historyResetCount;
    }

    /** Previous final output for diagnostics and frame.history. */
    public dev.luxloader.api.gpu.ImageHandle lastFrameOutput() {
        return lastFrameOutput;
    }

    /**
     * Applies FrameControl before graph recording, after previous-frame completion. Reload requests here
     * execute synchronously at this safe point. Requests from HostServices callbacks instead defer to
     * processPendingActions at the next frame boundary.
     */
    private void applyFrameControl(FrameControl control) {
        if (control == null) {
            return;
        }
        if (control.requestReload()) {
            diagnostics.info(tr("Pipeline requested reload: ") + control.reason());
            reload(tr("Pipeline frame control request"));
        }
        if (control.requestResize()) {
            diagnostics.info(tr("Pipeline requested size-dependent resource rebuild: ") + control.reason());
            // Explicitly readapt current dimensions; updateFrameSetup would ignore unchanged sizes.
            readaptPipelineToCurrentSetup(tr("Pipeline requested size-dependent resource rebuild"));
        }
        if (control.changesRenderScale()) {
            float scale = control.requestedRenderScale();
            if (Math.abs(scale - currentSetup.renderScale()) > 0.001f) {
                int renderWidth = Math.max(1, Math.round(currentSetup.displayWidth() * scale));
                int renderHeight = Math.max(1, Math.round(currentSetup.displayHeight() * scale));
                diagnostics.debug(tr("Dynamic resolution: ") + currentSetup.renderScale() + " → " + scale
                        + tr(" (render ") + renderWidth + "x" + renderHeight + "）");
                updateFrameSetup(new FrameSetup(currentSetup.displayWidth(), currentSetup.displayHeight(),
                        renderWidth, renderHeight, scale, currentSetup.colorFormat(),
                        currentSetup.depthFormat(), currentSetup.hdr(), currentSetup.sampleCount(),
                        currentSetup.frameIndex(), currentSetup.timing(), true,
                        currentSetup.uiSeparated(), currentSetup.hudHidden(),
                        currentSetup.pauseScreenOpen(), currentSetup.worldLoaded()));
            }
        }
        if (control.requestReset()) {
            historyResetCount++;
            diagnostics.debug(tr("Pipeline requested history reset: ") + control.reason());
        }
    }

    /**
     * Declares, compiles, records and submits the graph. Invalid graphs run no passes; partial execution
     * of a cyclic graph has undefined output semantics.
     */
    private boolean runFrameGraph(dev.luxloader.api.frame.FrameContext frame,
                                  HostAdapter.HostFrameTextures textures) {
        lastFrameGraphFailure = null;
        FrameGraphImpl graph = new FrameGraphImpl(
                graphicsQueue == null ? 0 : graphicsQueue.familyIndex(),
                hostFrameAllocate != null);
        graph.useResourceProvider(pipelineResources);
        provideHostResources(graph, textures);
        lastFrameGraph = graph;

        trace(tr("  encodeFrame started"));
        activePipeline.encodeFrame(graph, frame);
        trace(tr("  encodeFrame completed, nodes ") + graph.nodes().size() + tr("RenderDriverImpl.7aaf96ad52", " total"));
        graph.resolveDependencies();

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(graph.nodes(), graph.importedResources());
        graph.setCompiled(compiled);
        lastCompiled = compiled;

        if (!compiled.valid()) {
            diagnostics.error(tr("Frame graph compilation failed; skipping pipeline passes this frame: ") + compiled.error());
            lastFrameGraphFailure = compiled.error();
            return false;
        }
        for (String warning : compiled.warnings()) {
            diagnostics.debug(tr("Frame graph: ") + warning);
        }
        reportQueueFamilyOverrides();
        recordQueueGroupUsage(compiled);
        if (diagnostics.isVerbose()) {
            diagnostics.debug(tr("Frame graph execution order: ") + compiled.sequence());
        }

        // Defensively clear batch state after previous-frame reclamation to avoid waiting on stale semaphores.
        frameBatchSemaphores.clear();
        previousBatchSignal = null;
        frameQueueBatchCount = 0;

        trace(tr("  Recording passes"));
        FrameGraphExecution.Outcome outcome = FrameGraphExecution.run(
                graph, compiled, frame, message -> diagnostics.error(message),
                this::submitQueueBatch);
        trace(tr("  Recording completed: ") + outcome.describe());

        boolean requiredPassFailed = false;
        if (outcome.hasFailures()) {
            // Disable failed passes after this frame; preserve the first error instead of repeating it every frame.
            for (FrameGraphExecution.NodeFailure failure : outcome.failures()) {
                disableFailedPass(failure);
                requiredPassFailed |= failure.pass().required();
                if (failure.pass().required() && lastFrameGraphFailure == null) {
                    lastFrameGraphFailure = DiagnosticsImpl.stackTrace(failure.cause());
                }
            }
        }
        submitRecordedCommands();

        if (diagnostics.isVerbose()) {
            diagnostics.debug(tr("Frame graph") + outcome.describe());
        }
        return !requiredPassFailed;
    }

    /** Whether forced graphics fallback for host-resource access was reported. */
    private boolean queueFamilyOverrideNoted;

    /**
     * Reports once when host-resource access forces a pass to graphics. Host resources are EXCLUSIVE to
     * graphics, while loader-owned resources can be concurrently shared. To run later compute work, first
     * copy host inputs into loader-owned images on graphics.
     */
    /**
     * Records actual queue families and cumulative multiQueueFrames, distinguishing hardware queue
     * availability from real multi-queue execution.
     */
    private void recordQueueGroupUsage(FrameGraph.Compiled compiled) {
        if (diagnostics == null || compiled == null) {
            return;
        }
        var groups = compiled.queueGroups();
        java.util.LinkedHashSet<Integer> families = new java.util.LinkedHashSet<>();
        for (FrameGraph.QueueGroup group : groups) {
            families.add(group.queueFamily());
        }
        StringBuilder text = new StringBuilder();
        for (Integer family : families) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(family);
        }
        diagnostics.fact("frame.queueGroups", Integer.toString(groups.size()));
        diagnostics.fact("frame.queueFamilies", text.toString());
        if (families.size() > 1) {
            diagnostics.counter("frame.multiQueueFrames", 1L);
        }
    }

    private void reportQueueFamilyOverrides() {
        if (queueFamilyOverrideNoted) {
            return;
        }
        FrameGraphImpl graph = lastFrameGraph;
        if (graph == null) {
            return;
        }
        var overridden = graph.queueFamilyOverridden();
        if (overridden.isEmpty()) {
            return;
        }
        queueFamilyOverrideNoted = true;
        diagnostics.warn(tr("These passes requested non-graphics queues but read host resources; forced onto graphics: ")
                + String.join("、", overridden)
                + tr(". Host resources use VK_SHARING_MODE_EXCLUSIVE ")
                + tr("and belong to the graphics queue family; cross-family access is undefined. ")
                + tr("To run a pass on compute, avoid directly reading host resources. ")
                + tr("First copy the host image into a loader-owned texture on the graphics queue, then process that copy. ")
                + tr("This notice appears only once."));
    }

    /**
     * Fills measured GPU time into the driver's currentSetup, the authoritative production timing source.
     * Leaving it at zero would disable usefulness checks for frame generation despite available hardware.
     */
    private void applyMeasuredGpuFrameTime() {
        dev.luxloader.core.vulkan.VulkanCommands commands = vulkanCommands();
        if (commands == null || currentSetup == null) {
            return;
        }
        long measured = commands.lastGpuFrameTimeNs();
        if (measured <= 0L || measured == currentSetup.timing().gpuFrameTimeNs()) {
            return;
        }
        currentSetup = currentSetup.withTiming(currentSetup.timing().withGpuTime(measured));
        if (!gpuTimeReported) {
            // Report the first actual timestamp measurement rather than assuming success from an absence of errors.
            gpuTimeReported = true;
            diagnostics.info(tr("GPU frame timing ready: ") + measured + " ns（"
                    + String.format(java.util.Locale.ROOT, "%.2f", measured / 1_000_000.0)
                    + tr(" ms); frameGenerationUseful() can now use measured timing"));
        }
    }

    /** Whether GPU timing readiness was reported. */
    private boolean gpuTimeReported;

    /**
     * Fetches the presentation target before the pipeline decision. NONE is valid before acquisition or
     * without compatible adapter/backend support and preserves normal game presentation.
     */
    /** Records execution traces at info level so exported diagnostic logs retain them. */
    private void trace(String message) {
        // Force heartbeat markers to disk as they are written; native crashes may prevent normal log export.
        heartbeat(message);
        if (tracedFrames <= TRACE_FRAMES) {
            diagnostics.info(tr("[Frame] ") + message);
        }
    }

    /**
     * Bound forced heartbeat writes to startup frames to avoid recurring fsync overhead. Override with
     * -Dluxloader.heartbeat.frames=N when investigating later failures.
     */
    private static final long HEARTBEAT_FRAMES =
            Long.getLong("luxloader.heartbeat.frames", 10L);

    /** Heartbeat handle, null before opening. */
    private java.nio.channels.FileChannel heartbeatChannel;

    /** Permanently disable the heartbeat after a write failure to avoid affecting rendering or retrying every frame. */
    private boolean heartbeatBroken;

    /** Heartbeat sequence number for ordering evidence. */
    private long heartbeatSeq;

    /**
     * Count heartbeat frames in onFrameEnd, which runs even without an active pipeline. tracedFrames can
     * stop advancing and leave the heartbeat window open indefinitely.
     */
    private long heartbeatFrames;

    /**
     * Immediately persists completed frame-stage markers so native crashes that bypass JVM error/shutdown
     * handlers still leave a last-completed-stage trail. Gather this evidence before attributing a crash
     * to a mechanism.
     */
    private void heartbeat(String message) {
        // Use heartbeatFrames, not tracedFrames: an inactive pipeline returns before tracedFrames advances, otherwise creating unbounded per-frame forced writes even in menus.
        if (heartbeatBroken || heartbeatFrames > HEARTBEAT_FRAMES) {
            return;
        }
        try {
            java.nio.file.Path dir = contentDirectory;
            if (heartbeatChannel == null) {
                if (dir == null) {
                    heartbeatBroken = true;
                    return;
                }
                java.nio.file.Files.createDirectories(dir);
                heartbeatChannel = java.nio.channels.FileChannel.open(
                        dir.resolve("heartbeat.log"),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            }
            String line = java.time.Instant.now()
                    + " seq=" + (++heartbeatSeq)
                    + " frame=" + tracedFrames
                    + " " + message + System.lineSeparator();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(
                    line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (buf.hasRemaining()) {
                heartbeatChannel.write(buf);
            }
            // Force bytes to disk because a native crash may prevent any later flush.
            heartbeatChannel.force(false);
        } catch (Exception e) {
            // Disable failed heartbeats and report the reason so missing evidence is not mistaken for unreached code.
            heartbeatBroken = true;
            heartbeatChannel = null;
            if (diagnostics != null) {
                diagnostics.warn(tr("Frame heartbeat write failed; disabling this channel (future crashes may lack heartbeat evidence): ") + e);
            }
        }
    }

    /** Closes the heartbeat file. */
    private void closeHeartbeat() {
        java.nio.channels.FileChannel channel = heartbeatChannel;
        heartbeatChannel = null;
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception ignored) {
                // Ignore cleanup failure during process exit.
            }
        }
    }

    /** Readable handle description; null distinguishes absence from a zero native handle. */
    private static String handleOf(dev.luxloader.api.gpu.ImageHandle image) {
        return image == null ? "null" : "0x" + Long.toHexString(image.bits());
    }

    private void refreshPresentImage() {
        if (hostAdapter == null) {
            currentPresentImage = HostAdapter.HostPresentImage.NONE;
            return;
        }
        try {
            HostAdapter.HostPresentImage image = hostAdapter.presentImage();
            currentPresentImage = image == null ? HostAdapter.HostPresentImage.NONE : image;
            if (currentPresentImage.isUsable()) {
                if (diagnostics.isVerbose()) {
                    diagnostics.debug(tr("Current presentation target: ") + currentPresentImage.describe());
                }
            } else {
                // Presentation unavailability can be transient, so avoid per-frame logs. Record persistent inability through noteIssueOnce, counting affected runs rather than frames.
                String why = hostAdapter.presentImageNote();
                notePresentTargetIssue(tr("Host supplied no usable presentation target")
                        + (why == null || why.isBlank() ? "" : "：" + why));
            }
        } catch (RuntimeException e) {
            diagnostics.warn(tr("Host presentation target request failed: ") + e);
            currentPresentImage = HostAdapter.HostPresentImage.NONE;
            notePresentTargetIssue(tr("Host presentation target request threw: ") + e.getClass().getSimpleName());
        }
    }

    /** Records presentation unavailability, deduplicated by DeviceProfileStore.noteIssueOnce. */
    private void notePresentTargetIssue(String note) {
        if (deviceProfiles != null) {
            deviceProfiles.noteIssueOnce("present_target_unavailable", note);
        }
    }

    /** Current presentation target for diagnostics and decisions. */
    public HostAdapter.HostPresentImage currentPresentImage() {
        return currentPresentImage;
    }

    /** Registers host textures and frame boundaries. */
    private void provideHostResources(FrameGraphImpl graph, HostAdapter.HostFrameTextures textures) {
        // Register host color as externally owned with its measured format before binding or transitioning it. Host images stay GENERAL to match descriptor declarations; measured formats prevent guessed, incompatible image views. Log handles/formats for diagnosis.
        if (device != null && textures.color() != null && !textures.color().isNull()
                && device.commands() instanceof dev.luxloader.core.vulkan.VulkanCommands vk) {
            vk.registerExternalImage(textures.color().bits(), textures.colorFormat());
        }
        if (device != null && textures.depth() != null && !textures.depth().isNull()
                && device.commands() instanceof dev.luxloader.core.vulkan.VulkanCommands vk) {
            vk.registerExternalImage(textures.depth().bits(), textures.depthFormat());
        }
        if (device != null && currentScene != null
                && device.commands() instanceof dev.luxloader.core.vulkan.VulkanCommands vk) {
            for (dev.luxloader.api.scene.SceneImage image : currentScene.images()) {
                if (image.usable()) {
                    vk.registerExternalImage(image.handle().bits(), image.format().vkFormat());
                }
            }
        }
        trace(tr("  Host frame textures: color=") + handleOf(textures.color())
                + " depth=" + handleOf(textures.depth())
                + " ui=" + handleOf(textures.ui())
                + " swapchain=" + handleOf(textures.swapchain())
                + tr("; declared pipeline color format=") + currentSetup.colorFormat()
                + tr(", render ") + textures.renderWidth() + "x" + textures.renderHeight()
                + tr(", display ") + textures.displayWidth() + "x" + textures.displayHeight());
        graph.provide(FrameGraph.COLOR, textures.color(), tr("Host scene color"));
        graph.provide(FrameGraph.DEPTH, textures.depth(), tr("Host depth"));
        graph.provide(FrameGraph.MOTION, textures.motionVectors(), tr("Host motion vectors"));
        graph.provide(FrameGraph.EXPOSURE, textures.exposure(), tr("Automatic exposure"));
        graph.provide(FrameGraph.UI, textures.ui(), tr("Composited UI layer"));
        graph.provide(FrameGraph.SWAPCHAIN, textures.swapchain(), tr("Current swapchain image"));
        // Mark game-created resources as graphics-exclusive so graph queue selection prevents illegal cross-family access.
        graph.markForeign(FrameGraph.COLOR);
        graph.markForeign(FrameGraph.DEPTH);
        graph.markForeign(FrameGraph.MOTION);
        graph.markForeign(FrameGraph.EXPOSURE);
        graph.markForeign(FrameGraph.UI);
        graph.markForeign(FrameGraph.SWAPCHAIN);
        // History is loader-owned and concurrently shared, so do not mark it host-exclusive.
        if (lastFrameOutput != null) {
            graph.provide(FrameGraph.HISTORY, lastFrameOutput, tr("Previous pipeline output"));
        }
        // Boundary markers have names but no textures; register them for graph dumps.
        graph.define(FrameGraph.FRAME_BEGIN, tr("Frame-begin marker (readers run first)"));
        graph.define(FrameGraph.FRAME_END, tr("Frame-end marker (writers run last)"));
        graph.define(FrameGraph.FINAL, tr("Final output (writer determines presentation)"));
    }

    /**
     * Copies plugin output into a host-owned presentation texture because the game's view cannot wrap an
     * external VkImage. This compatibility path adds a fullscreen blit. Leave the target GENERAL: host
     * blitFromTexture declares GENERAL and inserts no source transition. TRANSFER_SRC_OPTIMAL would
     * violate that contract.
     */
    private boolean blitToPresentTarget(dev.luxloader.api.gpu.ImageHandle source) {
        presentProbeFrames++;
        HostAdapter.HostPresentImage target = currentPresentImage;
        if (device == null || graphicsQueue == null) {
            return false;
        }
        if (!target.isUsable()) {
            // Headless clients can consume the image handle directly. A Minecraft
            // adapter must provide a concrete image for the game's final blit.
            return hostAdapter == null;
        }
        if (source.bits() == target.vkImage()) {
            return true;
        }
        try {
            int sourceWidth = target.width();
            int sourceHeight = target.height();
            int sourceVkFormat = target.vkFormat();
            if (pipelineResources != null) {
                dev.luxloader.api.gpu.ImageDesc desc = pipelineResources.describe(source);
                if (desc != null) {
                    sourceWidth = desc.width();
                    sourceHeight = desc.height();
                    sourceVkFormat = desc.format().vkFormat();
                }
            }
            var view = dev.luxloader.api.gpu.ImageViewDesc.full();
            var targetHandle = dev.luxloader.api.gpu.ImageHandle.vkImage(target.vkImage(), tr("Host presentation texture"));

            // Diagnostic present.probeClear writes a known color into the same presentation target. Visible color isolates faults upstream in plugin output; absent color points to presentation transfer/visibility.
            if (Boolean.getBoolean("luxloader.present.probeClear")) {
                var probe = device.commands().begin("luxloader-probe-clear");
                probe.clearColor(targetHandle, view, 0.15f, 0.35f, 0.95f, 1.0f);
                probe.transition(targetHandle, view,
                        dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_WRITE,
                        dev.luxloader.api.gpu.GpuCommands.Access.SHADER_WRITE);
                probe.end();
                device.commands().flush(graphicsQueue, List.of(), List.of());
                trace(tr("  Probe: presentation target cleared to a known color (transfer bypassed)"));
                return true;
            }
            trace(tr("  Transferring pipeline output to presentation texture ") + sourceWidth + "x" + sourceHeight
                    + " -> " + target.width() + "x" + target.height());
            // Read the source before diagnostic transfer changes its layout, distinguishing original content from content potentially discarded by an UNDEFINED transition.
            if (Boolean.getBoolean("luxloader.test.pixelProbe") && !pixelProbed) {
                // Mark the source probe complete immediately and restore its layout afterward. Previously missing one-shot state caused a blocking readback every frame and left the source TRANSFER_SRC_OPTIMAL before later GENERAL writes.
                pixelProbed = true;
                try {
                    int[] srcPx = ((dev.luxloader.core.vulkan.VulkanCommands) device.commands())
                            .readPixelBlocking(source.bits(), sourceVkFormat, 0, 0);
                    diagnostics.info(tr("[Readback] Source texture BEFORE transfer (") + sourceWidth + "x" + sourceHeight
                            + tr(") pixel (0,0) raw bytes = ") + java.util.Arrays.toString(srcPx)
                            + "（VkFormat " + sourceVkFormat + tr(", expected not all zero)"));
                } catch (RuntimeException e) {
                    diagnostics.warn(tr("[Readback] Failed to read source pixel: ") + e);
                }
                // Restore the source after readback from TRANSFER_SRC_OPTIMAL to GENERAL for subsequent compute writes.
                try {
                    var restoreSource = device.commands().begin("luxloader-source-restore");
                    restoreSource.transition(source, view,
                            dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_READ,
                            dev.luxloader.api.gpu.GpuCommands.Access.SHADER_WRITE);
                    restoreSource.end();
                    device.commands().flush(graphicsQueue, List.of(), List.of());
                    trace(tr("  Source texture restored to GENERAL after readback"));
                } catch (RuntimeException e) {
                    diagnostics.warn(tr("[Readback] Failed to restore source image layout: ") + e);
                }
            }

            var commands = device.commands();
            var buffer = commands.begin("luxloader-present");
            buffer.blitImage(source, view, sourceWidth, sourceHeight,
                    targetHandle, view, target.width(), target.height());
            // Keep the host image GENERAL, matching the game's actual vkCmdBlitImage source layout. Use a same-layout barrier to make our writes visible; guessing TRANSFER_SRC_OPTIMAL previously produced invalid synchronization and black output.
            buffer.transition(targetHandle, view,
                    dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_WRITE,
                    dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_READ);
            // Write a timestamp at the final GPU work point; differences between consecutive frames supply frame timing.
            buffer.writeTimestamp();
            buffer.end();
            trace(tr("  Submitting transfer commands"));
            commands.flush(graphicsQueue, List.of(), List.of());
            trace(tr("  Transfer submitted"));
            // Commands have completed; read timestamps into currentSetup.
            if (!(commands instanceof dev.luxloader.core.vulkan.VulkanCommands vk)
                    || !vk.isHostFrameRecording()) {
                applyMeasuredGpuFrameTime();
            }
            // Persist a marker after the final query read to narrow failures between presentation submission and the next frame.
            trace(tr("  Frame end: GPU timing recorded"));

            // Frame capture has its own enablement and timing, independent of variance probing, so another diagnostic cannot suppress evidence collection.
            if (DUMP_AFTER >= 0 && !frameDumped && presentProbeFrames > DUMP_AFTER) {
                frameDumped = true;
                dumpPresentedFrame(target);
            }

            // Probe spatial variance after initial loading frames. A first-frame black pixel may be legitimate, and matching a clear color does not prove a composed image. Multiple distinct sampled tuples distinguish spatial content from a constant output.
            if (Boolean.getBoolean("luxloader.test.varianceProbe")
                    && !presentVarianceProbed && presentProbeFrames > PROBE_AFTER_FRAMES) {
                presentVarianceProbed = true;
                try {
                    var vk = (dev.luxloader.core.vulkan.VulkanCommands) commands;
                    int w = target.width();
                    int h = target.height();
                    int[][] points = {
                        {w / 8, h / 8}, {w / 2, h / 8}, {7 * w / 8, h / 8},
                        {w / 8, 7 * h / 8}, {w / 2, h / 2}, {7 * w / 8, 7 * h / 8}};
                    var distinct = new java.util.LinkedHashSet<String>();
                    int[] lo = {255, 255, 255, 255};
                    int[] hi = {0, 0, 0, 0};
                    for (int[] p : points) {
                        int[] px = vk.readPixelBlocking(target.vkImage(), target.vkFormat(),
                                p[0], p[1]);
                        distinct.add(java.util.Arrays.toString(px));
                        for (int c = 0; c < Math.min(4, px.length); c++) {
                            lo[c] = Math.min(lo[c], px[c]);
                            hi[c] = Math.max(hi[c], px[c]);
                        }
                    }
                    int spread = Math.max(Math.max(hi[0] - lo[0], hi[1] - lo[1]),
                            Math.max(hi[2] - lo[2], hi[3] - lo[3]));
                    diagnostics.info(tr("[Readback] Presentation texture variance: sampled ") + points.length
                            + tr(" points -> distinct pixel values ") + distinct.size() + tr(", maximum channel range ")
                            + spread + tr("; samples=") + distinct
                            + tr(" (source ") + sourceWidth + "x" + sourceHeight
                            + tr(" -> presentation ") + w + "x" + h + "）");
                    if (distinct.size() <= 1) {
                        diagnostics.warn(tr("[Readback] Presentation texture is CONSTANT: ")
                                + tr("check whether the plugin actually read its input."));
                    }
                } catch (RuntimeException e) {
                    diagnostics.warn(tr("[Readback] Presentation variance sampling failed: ") + e);
                }
                // readPixelBlocking leaves the image in TRANSFER_SRC_OPTIMAL. Restoring the
                // layout is NOT optional: the host blits this texture while declaring
                // srcImageLayout = GENERAL (VulkanGpuSurface.blitFromTexture emits no source
                // barrier), and the validation layer records the layout that was current when
                // the descriptor was written. Leaving it in TRANSFER_SRC_OPTIMAL produced a
                // live VUID-vkCmdDraw-None-09600 on this exact image on every following frame
                // ("expects ... TRANSFER_SRC_OPTIMAL -- instead, current layout is GENERAL").
                restorePresentLayout(target);
            }
            if (diagnostics.isVerbose()) {
                diagnostics.debug(tr("Pipeline output presented: ") + sourceWidth + "x" + sourceHeight
                        + " -> " + target.width() + "x" + target.height());
            }
            return true;
        } catch (RuntimeException e) {
            diagnostics.error(tr("Failed to blit pipeline output to the presentation target: ") + e);
            return false;
        }
    }

    /**
     * Captures one presented target after -Dluxloader.test.dumpFrameAfter=N; default -1 disables capture.
     * Presentation-path logs prove routing, not visible rendering effects. Save PPM to avoid a
     * java.desktop dependency, then inspect/convert externally.
     */
    private static final int DUMP_AFTER = Integer.getInteger("luxloader.test.dumpFrameAfter", -1);

    /** Capture one frame only to bound disk usage. */
    private boolean frameDumped;

    /**
     * Captures an image immediately for UI composition evidence after HUD drawing and before host blit.
     * The normal end-of-world capture occurs too early to verify HUD composition.
     * @return whether a file was written
     */
    public boolean dumpFrameNow(HostAdapter.HostPresentImage target) {
        if (target == null || !target.isUsable() || device == null) {
            return false;
        }
        try {
            dumpPresentedFrame(target);
            return true;
        } catch (RuntimeException e) {
            diagnostics.warn(tr("[Capture] Immediate capture failed: ") + e);
            return false;
        }
    }

    private void dumpPresentedFrame(HostAdapter.HostPresentImage target) {
        try {
            var vk = (dev.luxloader.core.vulkan.VulkanCommands) device.commands();
            int w = target.width();
            int h = target.height();
            byte[] raw = vk.readImageBlocking(target.vkImage(), target.vkFormat(), w, h);
            byte[] ppm = dev.luxloader.core.diag.FrameDump.toPpm(raw, w, h, target.vkFormat());
            java.nio.file.Path dir = java.nio.file.Path.of("luxloader", "frames");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path out = dir.resolve("presented-" + w + "x" + h
                    + "-frame" + presentProbeFrames + ".ppm");
            java.nio.file.Files.write(out, ppm);
            diagnostics.info(tr("[Capture] Written ") + out.toAbsolutePath() + tr(" (frame ") + presentProbeFrames
                    + tr(", ") + w + "x" + h + "，" + ppm.length + tr(" bytes)"));
        } catch (Throwable t) {
            diagnostics.warn(tr("[Capture] Failed: ") + t);
        } finally {
            restorePresentLayout(target);
        }
    }

    /**
     * Readbacks leave images TRANSFER_SRC_OPTIMAL. Always restore GENERAL before the host blit, which
     * declares that layout without a source barrier; otherwise later draws repeatedly violate VUID 09600.
     */
    private void restorePresentLayout(HostAdapter.HostPresentImage target) {
        try {
            var restore = device.commands().begin("luxloader-probe-restore");
            restore.transition(dev.luxloader.api.gpu.ImageHandle.vkImage(target.vkImage(), tr("Host presentation texture")),
                    dev.luxloader.api.gpu.ImageViewDesc.full(),
                    dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_READ,
                    dev.luxloader.api.gpu.GpuCommands.Access.SHADER_WRITE);
            restore.end();
            device.commands().flush(graphicsQueue, List.of(), List.of());
            trace(tr("  Presentation texture restored to GENERAL after readback"));
        } catch (RuntimeException e) {
            diagnostics.warn(tr("[Readback] Failed to restore presentation image layout: ") + e);
        }
    }

    /** Inter-batch semaphores returned at frame end. */
    private final List<dev.luxloader.api.gpu.GpuCommands.GpuSemaphore> frameBatchSemaphores =
            new ArrayList<>();
    /** Previous batch signal; null for the first batch. */
    private dev.luxloader.api.gpu.GpuCommands.GpuSemaphore previousBatchSignal;

    /** Submitted batch count for diagnostics. */
    private int frameQueueBatchCount;

    /**
     * Gets internal Vulkan submission helpers without exposing them in the plugin API.
     * Device-free/non-Vulkan tests fall back to the generic single-submission path.
     */
    private dev.luxloader.core.vulkan.VulkanCommands vulkanCommands() {
        if (device == null) {
            return null;
        }
        return device.commands() instanceof dev.luxloader.core.vulkan.VulkanCommands commands
                ? commands : null;
    }

    /**
     * Submits a completed queue-family batch at the execution-loop boundary. Each subsequent family waits
     * on the previous batch's semaphore. The last batch must not signal an unconsumed binary semaphore:
     * reusing a still-signaled semaphore violates VUID 00067 and can leak objects. Frame-end drain covers
     * final completion, and a single-family frame needs no inter-batch semaphores.
     */
    private void submitQueueBatch(int queueFamily, boolean last) {
        dev.luxloader.core.vulkan.VulkanCommands commands = vulkanCommands();
        if (commands == null || graphicsQueue == null) {
            return;
        }
        if (commands.pendingRecordingCount() == 0) {
            // An empty batch needs neither submission nor a semaphore; an unsignaled semaphore could hang the next batch.
            return;
        }
        GpuQueue queue = queueForFamily(queueFamily);
        dev.luxloader.api.gpu.GpuCommands.GpuSemaphore signal = last
                ? null
                : commands.acquireSemaphore("frame-batch-" + frameQueueBatchCount);
        List<dev.luxloader.api.gpu.GpuCommands.GpuSemaphore> wait = previousBatchSignal == null
                ? List.of() : List.of(previousBatchSignal);
        List<dev.luxloader.api.gpu.GpuCommands.GpuSemaphore> signals = signal == null
                ? List.of() : List.of(signal);
        int submitted = commands.flushAsync(queue, wait, signals);
        if (submitted == 0) {
            // Return the unused semaphore if submission unexpectedly recorded nothing; it will never be signaled.
            if (signal != null) {
                commands.releaseSemaphore(signal);
            }
            return;
        }
        previousBatchSignal = signal;
        if (signal != null) {
            frameBatchSemaphores.add(signal);
        }
        frameQueueBatchCount++;
    }

    /**
     * Resolves a queue for the selected family, falling back to graphics when unavailable. Submission must
     * still use commands valid for that family.
     */
    private GpuQueue queueForFamily(int family) {
        if (graphicsQueue != null && graphicsQueue.familyIndex() == family) {
            return graphicsQueue;
        }
        if (computeQueue != null && computeQueue.familyIndex() == family) {
            return computeQueue;
        }
        if (transferQueue != null && transferQueue.familyIndex() == family) {
            return transferQueue;
        }
        return graphicsQueue;
    }

    /** Returns batch semaphores only after drainSubmissions proves all waits consumed their signals. */
    private void releaseFrameBatchSemaphores() {
        dev.luxloader.core.vulkan.VulkanCommands commands = vulkanCommands();
        if (commands != null) {
            for (dev.luxloader.api.gpu.GpuCommands.GpuSemaphore semaphore : frameBatchSemaphores) {
                commands.releaseSemaphore(semaphore);
            }
        }
        frameBatchSemaphores.clear();
        previousBatchSignal = null;
        frameQueueBatchCount = 0;
    }

    /** Submits recorded commands; safely skips device-free offline tests. */
    private void submitRecordedCommands() {
        if (device == null || graphicsQueue == null) {
            return;
        }
        try {
            // Flush submits and drains all in-flight batches before presentation transfer reads their output; no additional semaphore is required afterward.
            int submitted = device.commands().flush(graphicsQueue, List.of(), List.of());
            if (submitted > 0) {
                submittedCommandBufferCount += submitted;
            }
        } catch (RuntimeException e) {
            diagnostics.error(tr("Failed to submit frame commands: ") + e);
        } finally {
            releaseFrameBatchSemaphores();
        }
    }

    /**
     * Disables failing PassHost instances and records the first cause so remaining passes can continue
     * without repetitive log floods. Custom RenderPass implementations lack a writable enable switch and
     * can only be reported.
     */
    private void disableFailedPass(FrameGraphExecution.NodeFailure failure) {
        failedPasses.merge(failure.nodeId().toString(), failure.describe(), (a, b) -> a);
        diagnostics.error(tr("Pass ") + failure.nodeId() + tr(" execution failed\n")
                + DiagnosticsImpl.stackTrace(failure.cause()));

        // Persist pass failures by stable pass name so recurring device/driver-specific failures remain visible next run.
        if (deviceProfiles != null) {
            deviceProfiles.noteIssue("frame_pass_failed:" + failure.nodeId(),
                    tr("Frame graph pass failed: ") + failure.cause());
        }

        if (failure.pass() instanceof dev.luxloader.api.pipeline.PassHost host) {
            host.setEnabled(false);
            diagnostics.error(tr("This pass is disabled; other passes continue. Reload configuration after fixing the issue."));
        } else {
            // Custom RenderPass implementations have no writable enable flag; report the limitation.
            diagnostics.warn(tr("This pass does not extend PassHost and cannot be disabled by the loader; ")
                    + tr("it will keep failing each frame. Handle errors in its implementation or use PassHost."));
        }
    }

    /** Frame-end resource reclamation. */
    @Override
    public void onFrameEnd() {
        onFrameEnd(false);
    }

    /** Frame-end callback carrying the host's already-completed queue guarantee, when available. */
    public void onFrameEnd(boolean hostGpuIdle) {
        if (!initialized.get()) {
            return;
        }
        dev.luxloader.core.vulkan.VulkanCommands vulkanCommands = vulkanCommands();
        boolean captureRetirement = vulkanCommands != null && vulkanCommands.hasPendingImageCapture();
        if (vulkanCommands != null) {
            vulkanCommands.pollHostFrameCompletions();
            if (hostGpuIdle) {
                vulkanCommands.retireHostFramesAfterIdle();
            } else if (captureRetirement) {
                // An empty queue submission places a nonblocking fence after the host's frame commands.
                vulkanCommands.finishHostFrameAsync(graphicsQueue);
            }
        }
        if (deferredHostFailure != null) {
            DeferredHostFailure failure = deferredHostFailure;
            deferredHostFailure = null;
            // Loader callbacks reach here only after the host frame has been
            // submitted and its queue has finished using plugin resources.
            failPipelineAfterCurrentFrame(failure.id(), failure.state(),
                    failure.reason(), failure.detail());
        }
        // Heartbeat boundary around host blit/present between onBeforePresent and frame end, narrowing where native failure occurred.
        trace(tr("Frame end"));
        // Advance the frame after tracing its end so the final allowed heartbeat frame is complete.
        heartbeatFrames++;
        try {
            if (activePipeline != null && !activePipeline.passes().isEmpty()) {
                activePipeline.endFrame(currentSetup.frameIndex());
            }
        } catch (RuntimeException e) {
            reportFrameFailure(tr("Frame end processing"), e);
        } finally {
            if (activePipeline != null && currentScene != null && currentScene.worldLoaded()) {
                previousFrameCamera = currentScene.camera();
                previousFrameDimension = currentScene.environment().dimensionName();
            } else {
                previousFrameCamera = null;
                previousFrameDimension = "";
            }
            onFrameFinished();
        }
    }

    /** Whether an asynchronous capture is awaiting completion and the host need not force device idle. */
    public boolean requiresAsyncCaptureRetirement() {
        var commands = vulkanCommands();
        return commands != null && commands.hasPendingImageCapture();
    }

    /** Swapchain resize notification. */
    @Override
    public void onResize(int displayWidth, int displayHeight) {
        if (displayWidth <= 0 || displayHeight <= 0) {
            return;
        }
        FrameSetup updated = new FrameSetup(displayWidth, displayHeight,
                currentSetup.renderWidth(), currentSetup.renderHeight(), currentSetup.renderScale(),
                currentSetup.colorFormat(), currentSetup.depthFormat(), currentSetup.hdr(),
                currentSetup.sampleCount(), currentSetup.frameIndex(), currentSetup.timing(), true,
                currentSetup.uiSeparated(), currentSetup.hudHidden(), currentSetup.pauseScreenOpen(),
                currentSetup.worldLoaded());
        updateFrameSetup(updated);
    }

    /**
     * Registers an adapter capability under the same precedence rules as plugins; lower levels do not
     * replace higher levels.
     */
    public void registerCapability(dev.luxloader.api.capability.CapabilityDescriptor descriptor) {
        if (capabilities == null || descriptor == null) {
            return;
        }
        capabilities.register(descriptor);
    }

    /** Internal VulkanDispatch for adapter request inspection. */
    public VulkanDispatch vulkanDispatch() {
        return vulkanDispatch;
    }

    /** Graphics queue, null before device readiness. */
    public GpuQueue graphicsQueue() {
        return graphicsQueue;
    }

    /** Compute queue, or graphics without a dedicated queue. */
    public GpuQueue computeQueue() {
        return computeQueue;
    }

    /** Transfer queue, or graphics without a dedicated queue. */
    public GpuQueue transferQueue() {
        return transferQueue;
    }

    /** Whether the device is ready. */
    public boolean deviceReady() {
        return device != null;
    }

    /** Loaded plugin instances for adapters/tests. */
    public List<PipelinePlugin> plugins() {
        return List.copyOf(plugins);
    }

    /** Plugin HostServices for test assertions. */
    public Optional<HostServicesImpl> hostServicesFor(String pluginId) {
        return Optional.ofNullable(hostServices.get(pluginId));
    }

    /** Loader metadata identifier. */
    public static LuxMod loaderMod() {
        return LOADER_MOD;
    }
}
