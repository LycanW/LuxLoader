package dev.luxloader.core.runtime;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.event.*;
import dev.luxloader.api.host.HostAdapter;
import dev.luxloader.api.plugin.RenderDriver;
import dev.luxloader.api.scene.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static dev.luxloader.api.presentation.PresentationService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual driver/state/event/contribution/plan path, with a controlled CPU pipeline and sound port. */
class PresentationDriverTest {
    @TempDir Path output;
    RenderDriverImpl driver;
    AtomicLong clock = new AtomicLong();
    java.util.concurrent.atomic.AtomicBoolean resourceStateFailure = new java.util.concurrent.atomic.AtomicBoolean();
    PresentationHubTest.SoundPort sound = new PresentationHubTest.SoundPort();
    PresentationHubTest.Pipeline pipeline = new PresentationHubTest.Pipeline();
    GpuId type = FakeTestPlugin.ID.child("landing");

    @AfterEach void close() { if (driver != null) driver.close(); RecordingLifecyclePlugin.reset(); }

    void initialize() {
        driver = new RenderDriverImpl(output, null, clock::get);
        driver.initialize(RenderDriver.DeviceRequest.attachedToGame());
        driver.attachSoundBackend(sound);
        driver.attachHostAdapter(new HostAdapter() {
            public String hostName() { return "Controlled presentation scene"; }
            public dev.luxloader.api.gpu.GpuCapabilities capabilities() { return null; }
            public HostDevice device() { return null; }
            public HostFrameTextures frameTextures() { return HostFrameTextures.EMPTY; }
            public HostFrameHook frameHook() { return HostFrameHook.NONE; }
            public SceneSnapshot sceneSnapshot() { return PresentationHubTest.scene(); }
            public ResourceAccess resources() {
                return new ResourceAccess() {
                    public long revision() { return 0; }
                    public Optional<byte[]> read(String namespace, String path) { return Optional.empty(); }
                    public dev.luxloader.api.resource.ResourceState state() {
                        if (resourceStateFailure.get()) throw new IllegalStateException("Host resource state failed");
                        return ResourceAccess.super.state();
                    }
                };
            }
            public void registerCapabilities(java.util.function.Consumer<dev.luxloader.api.capability.CapabilityDescriptor> sink) { }
        });
        pipeline.supported = Set.of(type);
    }

    void bindControlledPipeline() throws Exception {
        // Activation normally requires Vulkan. Bind only this controlled CPU fixture; subsequent
        // state, event, frame-begin, contribution merge and plan calls are production methods.
        var selected = RenderDriverImpl.class.getDeclaredField("activePipeline"); selected.setAccessible(true); selected.set(driver, pipeline);
        var id = RenderDriverImpl.class.getDeclaredField("activePipelineId"); id.setAccessible(true); id.set(driver, FakeTestPlugin.ALWAYS_OK_PIPELINE);
    }
    void point(long world, boolean loaded, boolean paused) {
        driver.observeClientStateSafePoint(paused, world, (sequence, time) -> PresentationHubTest.snapshot(sequence, time, world, loaded));
    }

    @Test void landingEventActuallyStartsBothOutputsAndReachesTheActivePipelineSceneConsumer() throws Exception {
        initialize(); bindControlledPipeline();
        var host = driver.hostServicesFor(FakeTestPlugin.ID.toString()).orElseThrow();
        var views = new ArrayList<View>();
        var registered = host.presentations().register(new Definition(type, (view, scene) -> { views.add(view); return List.of(PresentationHubTest.mesh(view)); },
                PresentationHubTest.SOUND, Category.PLAYERS, false));
        var handles = new ArrayList<Handle>();
        host.clientEvents().subscribe(batch -> {
            for (var event : batch.events()) if (event instanceof BehaviorEvent behavior && behavior.kind() == BehaviorEvent.Kind.LANDING)
                handles.add(registered.start(new Request(batch.snapshot().world(), batch.snapshot().time().elapsedNanos(), 1_000_000_000L,
                        Parameters.at(3, 64, 5))));
        });
        point(1, true, false);
        assertTrue(driver.captureClientBehaviorEvent(1, BehaviorEvent.Kind.LANDING, BehaviorEvent.Phase.OBSERVED_TRANSITION,
                BehaviorEvent.Source.CLIENT_OBSERVATION, EventValue.ObjectValue::empty));
        clock.set(100_000_000); driver.observeClientStateTick(false, (sequence, time) -> PresentationHubTest.snapshot(sequence, time, 1, true));
        point(1, true, false);
        assertEquals(1, handles.size()); assertEquals(1, sound.voices.size());
        driver.onFrameBegin(); driver.activeWorldFramePlan();
        assertNotNull(pipeline.consumed); assertEquals(1, pipeline.consumed.dynamicMeshes().size());
        assertEquals(3, pipeline.consumed.dynamicMeshes().getFirst().mesh().originX());
        assertEquals(VisualStatus.CONTRIBUTED, handles.getFirst().snapshot().visual());
        assertEquals(views.getFirst().source(), sound.voices.getFirst().source);
        assertEquals(views.getFirst().time().elapsedNanos(), handles.getFirst().snapshot().view().time().elapsedNanos());
        handles.getFirst().close(); driver.onFrameBegin(); driver.activeWorldFramePlan();
        assertTrue(pipeline.consumed.dynamicMeshes().isEmpty()); assertEquals(1, sound.voices.getFirst().cancels);
    }

    @Test void reloadInvalidatesOldServicesRegistrationsAndVoicesBeforeSameIdCanRegisterAgain() throws Exception {
        initialize(); bindControlledPipeline();
        var old = driver.hostServicesFor(FakeTestPlugin.ID.toString()).orElseThrow().presentations();
        var registration = old.register(new Definition(type, null, PresentationHubTest.SOUND, Category.PLAYERS, false));
        var handle = registration.start(PresentationHubTest.request(0, 1_000_000_000)); point(1, true, false);
        driver.reload("Presentation cleanup");
        assertEquals(State.CANCELLED, handle.snapshot().state()); assertEquals(1, sound.voices.getFirst().cancels);
        assertTrue(registration.isClosed()); assertThrows(IllegalStateException.class, () -> old.register(new Definition(type, null, PresentationHubTest.SOUND, Category.PLAYERS, false)));
        var replacement = driver.hostServicesFor(FakeTestPlugin.ID.toString()).orElseThrow().presentations().register(new Definition(type, null,
                PresentationHubTest.SOUND, Category.PLAYERS, false));
        bindControlledPipeline(); var newHandle = replacement.start(PresentationHubTest.request(0, 1_000_000_000));
        registration.close(); handle.close(); point(1, true, false);
        assertEquals(State.RUNNING, newHandle.snapshot().state()); assertEquals(0, sound.voices.getLast().cancels);
    }

    @Test void disableAndWorldExitStopAudioWithoutRequiringAnotherRenderFrame() throws Exception {
        initialize(); bindControlledPipeline();
        var registration = driver.hostServicesFor(FakeTestPlugin.ID.toString()).orElseThrow().presentations()
                .register(new Definition(type, null, PresentationHubTest.SOUND, Category.PLAYERS, false));
        var first = registration.start(PresentationHubTest.request(0, 1_000_000_000)); point(1, true, false);
        driver.setPipelineEnabled(FakeTestPlugin.ALWAYS_OK_PIPELINE, false);
        assertEquals(State.CANCELLED, first.snapshot().state()); assertEquals(1, sound.voices.getFirst().cancels);
        var disabled = registration.start(PresentationHubTest.request(0, 1_000_000_000)); point(1, true, false);
        assertEquals(State.CANCELLED, disabled.snapshot().state()); assertEquals(1, sound.voices.size());
        driver.setPipelineEnabled(FakeTestPlugin.ALWAYS_OK_PIPELINE, true);
        var second = registration.start(PresentationHubTest.request(0, 1_000_000_000)); point(1, true, false);
        point(2, false, false); assertEquals(State.CANCELLED, second.snapshot().state()); assertEquals(1, sound.voices.getLast().cancels);
    }

    @Test void partialPluginLoadFailureCancelsQueuedPresentationsAndNoMetadataAutoPlays() {
        RecordingLifecyclePlugin.reset(); RecordingLifecyclePlugin.presentationOnLoad = true;
        RecordingLifecyclePlugin.armNextLoadFailure(); initialize();
        var plugin = RecordingLifecyclePlugin.instances().values().stream().filter(RecordingLifecyclePlugin::armedToFailLoad).findFirst().orElseThrow();
        assertTrue(plugin.presentationRegistration.isClosed()); assertEquals(State.CANCELLED, plugin.presentationHandle.snapshot().state());
        assertTrue(sound.voices.isEmpty());
        driver.close(); driver = null; assertEquals(1, plugin.unloadCount());
    }

    @Test void aLoadedPluginWithOnlyPresentationMetadataDoesNotRequestClientStatePayloads() {
        initialize();
        driver.hostServicesFor(FakeTestPlugin.ID.toString()).orElseThrow().presentations()
                .register(new Definition(type, null, PresentationHubTest.SOUND, Category.PLAYERS, false));
        driver.observeClientStateTick(false, (sequence, time) -> { fail("Metadata must not demand snapshots"); return null; });
        driver.observeClientStateSafePoint(false, 1, (sequence, time) -> { fail("No output or observer demand"); return null; });
        assertTrue(sound.voices.isEmpty());
    }

    @Test void failedStateCaptureAndResourceLookupCancelAudioWithoutAnotherFrame() throws Exception {
        initialize(); bindControlledPipeline();
        var registration = driver.hostServicesFor(FakeTestPlugin.ID.toString()).orElseThrow().presentations()
                .register(new Definition(type, null, PresentationHubTest.SOUND, Category.PLAYERS, false));
        var first = registration.start(PresentationHubTest.request(0, 1_000_000_000)); point(1, true, false);
        driver.observeClientStateTick(false, (sequence, time) -> null);
        driver.observeClientStateSafePoint(false, 1, (sequence, time) -> null);
        assertEquals(Reason.STATE_UNAVAILABLE, first.snapshot().reason()); assertEquals(1, sound.voices.getFirst().cancels);
        var second = registration.start(PresentationHubTest.request(0, 1_000_000_000)); point(1, true, false);
        resourceStateFailure.set(true); point(1, true, false);
        assertEquals(Reason.RESOURCE_CHANGED, second.snapshot().reason()); assertEquals(1, sound.voices.getLast().cancels);
    }
}
