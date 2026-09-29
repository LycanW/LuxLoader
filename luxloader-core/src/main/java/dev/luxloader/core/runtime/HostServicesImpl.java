package dev.luxloader.core.runtime;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityRegistry;
import dev.luxloader.api.config.ConfigSchema;
import dev.luxloader.api.diag.Diagnostics;
import dev.luxloader.api.event.ClientEventBatch;
import dev.luxloader.api.event.ClientEventService;
import dev.luxloader.api.event.ClientEventSubscription;
import dev.luxloader.api.event.CustomEventRegistration;
import dev.luxloader.api.event.CustomEventType;
import dev.luxloader.api.event.EventTypeId;
import dev.luxloader.api.event.EventValue;
import dev.luxloader.api.nativebridge.NativeBridge;
import dev.luxloader.api.pipeline.PipelineDescriptor;
import dev.luxloader.api.pipeline.PipelineSettings;
import dev.luxloader.api.pipeline.RenderPipeline;
import dev.luxloader.api.plugin.HostServices;
import dev.luxloader.api.state.ClientStateBatch;
import dev.luxloader.api.state.ClientStateService;
import dev.luxloader.api.state.ClientStateSnapshot;
import dev.luxloader.api.state.ClientStateSubscription;
import dev.luxloader.api.config.ConfigView;
import dev.luxloader.core.capability.CapabilityRegistryImpl;
import dev.luxloader.core.diag.DiagnosticsImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Plugin-facing HostServices implementation. Validate conflicts/descriptors/requirements during
 * registration, store factories until activation to avoid premature GPU allocation, and report
 * registration failures without throwing through plugin onLoad or affecting other plugins.
 *
 * <p>The plugin ID stays stable across a reload, so it cannot identify an instance. Every instance
 * owns one services object with its own activation state: after the instance is unloaded or its
 * load failed, {@link #deactivate()} makes every entry point that could modify driver state throw.
 * A plugin that keeps an old reference therefore cannot deregister, re-register or switch anything
 * owned by the instance that replaced it.
 */
public final class HostServicesImpl implements HostServices {

    private static final AtomicLong NEXT_INSTANCE_TOKEN = new AtomicLong();

    /** Registered descriptor, factory and provider plugin. */
    public record Registration(
            GpuId id,
            PipelineDescriptor descriptor,
            Supplier<RenderPipeline> factory,
            String providerPlugin) {
    }

    private final LuxMod mod;
    private final CapabilityRegistryImpl capabilities;
    private final Diagnostics diagnostics;
    private final NativeBridge nativeBridge;
    private final RuntimeContext runtime;
    private final long instanceToken = NEXT_INSTANCE_TOKEN.incrementAndGet();
    private final ClientStateService clientState;
    private final ClientEventService clientEvents;

    /**
     * Whether this instance still owns its registrations. Offline construction without a runtime
     * context has no driver state to protect and therefore stays active.
     */
    private final java.util.concurrent.atomic.AtomicBoolean active;

    private ConfigSchema schema = ConfigSchema.builder().build();
    private PipelineSettings settings;
    private final Map<GpuId, Registration> registrations = new LinkedHashMap<>();
    private final List<String> nativeLibraryPaths = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private String gameVersion = "";

    /** Minimal host/registry callback context without exposing the full driver. */
    public interface RuntimeContext {
        /**
         * Records a scene contribution owned by {@code ownerPluginId}; the owner is supplied by the
         * calling plugin's services object, so a plugin cannot register under another owner.
         */
        default void onSceneContributorRegistered(String ownerPluginId, GpuId id,
                                                  dev.luxloader.api.scene.SceneContributor contributor) {
            throw new UnsupportedOperationException("Scene contributor registry unavailable");
        }

        /** Releases a contribution previously registered by {@code ownerPluginId}. */
        default void onSceneContributorDeregistered(String ownerPluginId, GpuId id) {
            throw new UnsupportedOperationException("Scene contributor registry unavailable");
        }

        default dev.luxloader.api.scene.ResourceAccess resources() {
            return dev.luxloader.api.scene.ResourceAccess.EMPTY;
        }

        default dev.luxloader.api.resource.ResourcePreparationService resourcePreparation(long ownerInstanceToken) {
            return dev.luxloader.api.resource.ResourcePreparationService.UNAVAILABLE;
        }

        default void releaseResourceOwner(long ownerInstanceToken) { }

        /** Returns the per-plugin-instance client state service. */
        default ClientStateService clientState(long ownerInstanceToken) {
            return ClientStateService.EMPTY;
        }

        /** Cancels state subscriptions created by this exact plugin instance. */
        default void releaseClientStateOwner(long ownerInstanceToken) { }

        /** Returns the client event service scoped to one exact plugin instance. */
        default ClientEventService clientEvents(long ownerInstanceToken, String ownerPluginId) {
            return ClientEventService.EMPTY;
        }

        /** Cancels event subscriptions and custom event types created by this exact plugin instance. */
        default void releaseClientEventsOwner(long ownerInstanceToken) { }

        void onPipelineRegistered(Registration registration);

        void onReloadRequested(String reason);

        void onPipelineSwitchRequested(GpuId id, String reason);

        Optional<GpuId> currentPipeline();

        String loaderVersion();

        ConfigSchema schemaFor(String pluginId);

        PipelineSettings settingsFor(String pluginId, ConfigSchema schema);
    }

    public HostServicesImpl(LuxMod mod, CapabilityRegistryImpl capabilities, Diagnostics diagnostics,
                            NativeBridge nativeBridge, RuntimeContext runtime) {
        this.mod = mod;
        this.capabilities = capabilities;
        this.diagnostics = diagnostics;
        this.nativeBridge = nativeBridge;
        this.runtime = runtime;
        this.active = new java.util.concurrent.atomic.AtomicBoolean(runtime != null);
        this.clientState = runtime == null ? ClientStateService.EMPTY
                : guardedClientState(runtime.clientState(instanceToken));
        this.clientEvents = runtime == null ? ClientEventService.EMPTY
                : guardedClientEvents(runtime.clientEvents(instanceToken, ownerPluginId()));
    }

    // Instance activation.

    /**
     * Whether this services object may still modify driver state. False after {@link #deactivate()},
     * which the loader calls when the owning plugin instance is unloaded or its load failed.
     */
    public boolean isActive() {
        return active.get();
    }

    /**
     * Marks this instance as replaced or failed, so a plugin holding the old reference cannot keep
     * using the same plugin ID to change the current session. Idempotent, and safe to call while the
     * instance is still cleaning up: read-only services remain usable for diagnostics.
     */
    public void deactivate() {
        if (active.compareAndSet(true, false) && runtime != null) {
            try {
                runtime.releaseResourceOwner(instanceToken);
            } catch (RuntimeException | LinkageError e) {
                diagnostics.warn(tr("Failed to cancel resource preparation for plugin ") + ownerPluginId() + ": " + e);
            }
            try {
                runtime.releaseClientStateOwner(instanceToken);
            } catch (RuntimeException e) {
                diagnostics.warn("Failed to cancel client state subscriptions for plugin " + ownerPluginId() + ": " + e);
            }
            try {
                runtime.releaseClientEventsOwner(instanceToken);
            } catch (RuntimeException e) {
                diagnostics.warn("Failed to cancel client event subscriptions for plugin " + ownerPluginId() + ": " + e);
            }
        }
    }

    /** Rejects a state-changing call from a services object whose owner is no longer loaded. */
    private void requireActive() {
        if (!active.get()) {
            throw new IllegalStateException("Plugin instance " + ownerPluginId()
                    + " is no longer loaded; this service object cannot modify loader state");
        }
    }

    // ---------------- HostServices ----------------

    @Override
    public LuxMod mod() {
        return mod;
    }

    @Override public dev.luxloader.api.scene.ResourceAccess resources() { return runtime.resources(); }

    @Override public dev.luxloader.api.resource.ResourcePreparationService resourcePreparation() {
        return () -> {
            requireActive();
            if (runtime == null) return dev.luxloader.api.resource.ResourcePreparationService.UNAVAILABLE.openScope();
            return runtime.resourcePreparation(instanceToken).openScope();
        };
    }

    @Override public ClientStateService clientState() { return clientState; }

    @Override public ClientEventService clientEvents() { return clientEvents; }

    private ClientStateService guardedClientState(ClientStateService delegate) {
        if (delegate == null || delegate == ClientStateService.EMPTY) return ClientStateService.EMPTY;
        return new ClientStateService() {
            @Override public Optional<ClientStateSnapshot> current() {
                return active.get() ? delegate.current() : Optional.empty();
            }

            @Override public ClientStateSubscription subscribe(Consumer<ClientStateBatch> listener) {
                requireActive();
                return delegate.subscribe(listener);
            }
        };
    }

    private ClientEventService guardedClientEvents(ClientEventService delegate) {
        if (delegate == null || delegate == ClientEventService.EMPTY) return ClientEventService.EMPTY;
        return new ClientEventService() {
            @Override public ClientEventSubscription subscribe(Consumer<ClientEventBatch> listener) {
                requireActive();
                return delegate.subscribe(listener);
            }

            @Override public ClientEventSubscription subscribeCustom(CustomEventType type,
                                                                       Consumer<ClientEventBatch> listener) {
                requireActive();
                return delegate.subscribeCustom(type, listener);
            }

            @Override public CustomEventRegistration registerCustomType(EventTypeId id, int version) {
                requireActive();
                return delegate.registerCustomType(id, version);
            }

            @Override public void publish(CustomEventType type, EventValue.ObjectValue payload) {
                requireActive();
                delegate.publish(type, payload);
            }
        };
    }

    /**
     * This plugin's ID, used as the owner of everything it registers. Recorded by this object because
     * it is what registration calls pass to the runtime, so later cleanup compares like for like.
     */
    public String ownerPluginId() {
        LuxMod metadata = mod();
        return metadata == null ? getClass().getName() : metadata.id().toString();
    }

    @Override public void registerSceneContributor(GpuId id, dev.luxloader.api.scene.SceneContributor contributor) {
        requireActive();
        runtime.onSceneContributorRegistered(ownerPluginId(),
                java.util.Objects.requireNonNull(id),
                java.util.Objects.requireNonNull(contributor));
    }

    @Override public void deregisterSceneContributor(GpuId id) {
        requireActive();
        runtime.onSceneContributorDeregistered(ownerPluginId(),
                java.util.Objects.requireNonNull(id));
    }

    @Override
    public void registerPipeline(GpuId id, PipelineDescriptor descriptor,
                                 Supplier<RenderPipeline> factory) {
        requireActive();
        if (id == null || descriptor == null || factory == null) {
            warn(tr("registerPipeline received a null argument (id=") + id + tr("); ignored"));
            return;
        }
        if (!id.equals(descriptor.id())) {
            warn(tr("Pipeline ID mismatch: registered ID=") + id + tr(", descriptor ID=") + descriptor.id()
                    + tr(". Registered using the supplied ID; fix the plugin declaration."));
        }
        Registration existing = registrations.get(id);
        if (existing != null) {
            warn(tr("Conflicting pipeline ID: ") + id + tr(" is already registered by plugin ") + existing.providerPlugin()
                    + tr("; registration from ") + mod.id() + tr(" was ignored.")
                    + tr("Use a unique GpuId for the pipeline."));
            return;
        }

        Registration registration = new Registration(id, descriptor, factory, mod.id().toString());
        registrations.put(id, registration);

        diagnostics.info(tr("Registered pipeline: ") + descriptor.describe());
        if (descriptor.experimental()) {
            diagnostics.warn(tr("Pipeline ") + id + tr(" is experimental; explicitly enable it in configuration before activation"));
        }
        if (descriptor.exclusive()) {
            diagnostics.warn(tr("Pipeline ") + id + tr(" declares exclusive rendering; activation disables vanilla scene drawing"));
        }

        try {
            runtime.onPipelineRegistered(registration);
        } catch (RuntimeException e) {
            diagnostics.error(tr("Failed to notify host of pipeline registration: ") + id, e);
        }
    }

    @Override
    public void registerCapability(CapabilityDescriptor descriptor) {
        requireActive();
        if (descriptor == null) {
            return;
        }
        // Record the claim only when it is accepted, so a rejected submission cannot later be used to
        // revoke a capability another provider owns.
        boolean accepted = capabilities.claim(ownerPluginId(), descriptor);
        if (!accepted && diagnostics.isVerbose()) {
            diagnostics.debug(tr("Capability ") + descriptor.id() + tr(" registration rejected (a higher support level is already recorded)"));
        }
    }

    @Override
    public void warn(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        warnings.add(message);
        diagnostics.warn(message);
    }

    /**
     * Registers a bundled native directory for extraction and library lookup. Extraction failure disables
     * native availability without aborting loading.
     */
    @Override
    public void registerNativeLibraryPath(String relativePath) {
        requireActive();
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        nativeLibraryPaths.add(relativePath);
        diagnostics.debug(tr("Registered native library directory: ") + relativePath);
    }

    @Override
    public NativeBridge nativeBridge() {
        return nativeBridge;
    }

    @Override
    public ConfigSchema declareConfig() {
        return schema;
    }

    @Override
    public PipelineSettings settings() {
        return settings;
    }

    @Override
    public Diagnostics diagnostics() {
        return diagnostics;
    }

    @Override
    public CapabilityRegistry capabilities() {
        return capabilities;
    }

    @Override
    public List<GpuId> registeredPipelineIds() {
        return List.copyOf(registrations.keySet());
    }

    @Override
    public String loaderVersion() {
        return runtime.loaderVersion();
    }

    @Override
    public String gameVersion() {
        return gameVersion;
    }

    @Override
    public void requestReload(String reason) {
        requireActive();
        diagnostics.info(tr("Plugin ") + mod.id() + tr(" requested reload: ")
                + (reason == null ? tr("(no reason given)") : reason));
        runtime.onReloadRequested(reason);
    }

    @Override
    public void requestPipelineSwitch(GpuId pipelineId, String reason) {
        requireActive();
        diagnostics.info(tr("Plugin ") + mod.id() + tr(" requested pipeline switch to ") + pipelineId + ": "
                + (reason == null ? tr("(no reason given)") : reason));
        runtime.onPipelineSwitchRequested(pipelineId, reason);
    }

    @Override
    public Optional<GpuId> activePipeline() {
        return runtime.currentPipeline();
    }

    // Loader internals.

    /** Binds the plugin schema and runtime settings. */
    public void bindConfig(ConfigSchema newSchema, PipelineSettings newSettings) {
        this.schema = newSchema == null ? ConfigSchema.builder().build() : newSchema;
        this.settings = newSettings;
    }

    /** Sets the game version for diagnostics and compatibility. */
    public void setGameVersion(String version) {
        this.gameVersion = version == null ? "" : version;
    }

    /** Pipelines registered by this plugin. */
    public List<Registration> registrations() {
        return List.copyOf(registrations.values());
    }

    /**
     * Capabilities this instance currently claims. The registry keeps the ownership, so this is a
     * convenience view for diagnostics; releasing them is {@code releaseClaims(ownerPluginId())} on
     * the registry.
     */
    public List<String> capabilityIds() {
        return capabilities.claimsOf(ownerPluginId()).stream()
                .map(CapabilityDescriptor::id)
                .toList();
    }

    /** Native directories declared by this plugin. */
    public List<String> nativeLibraryPaths() {
        return List.copyOf(nativeLibraryPaths);
    }

    /** Plugin warnings for diagnostics. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
