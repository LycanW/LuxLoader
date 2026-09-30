package dev.luxloader.core.runtime;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.event.EventValue;
import dev.luxloader.api.host.HostSoundBackend;
import dev.luxloader.api.pipeline.*;
import dev.luxloader.api.presentation.SoundAsset;
import dev.luxloader.api.resource.*;
import dev.luxloader.api.scene.*;
import dev.luxloader.api.state.ClientStateSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.luxloader.api.presentation.PresentationService.*;
import static org.junit.jupiter.api.Assertions.*;

/** CPU scheduling tests; the controlled sound port proves routing, not native audibility. */
class PresentationHubTest {
    static final GpuId OWNER = new GpuId("test", "presentation");
    static final GpuId TYPE = OWNER.child("landing");
    static final ClientStateSnapshot.WorldSession WORLD = new ClientStateSnapshot.WorldSession(1, true, "minecraft:overworld");
    static final SoundAsset SOUND = new SoundAsset.HostEvent(new ResourceKey("minecraft", "entity.player.splash"));
    static final Parameters PARAMS = Parameters.at(1, 64, 2);

    static class SoundPort implements HostSoundBackend {
        final List<SoundVoice> voices = new ArrayList<>();
        int observeCalls;
        public Capabilities capabilities() { return new Capabilities(true, true, true, true, true, true, true); }
        public Voice start(Source source, SoundAsset asset, Category category, boolean looping, Parameters parameters) {
            var voice = new SoundVoice(source, parameters); voices.add(voice); return voice;
        }
        public void observe(long generation, boolean enabled, java.util.function.Consumer<HostSound> sink) { observeCalls++; }
    }
    static final class SoundVoice implements HostSoundBackend.Voice {
        final Source source;
        Parameters parameters;
        int cancels;
        boolean paused, active = true;
        SoundStatus result = SoundStatus.HOST_STARTED;
        SoundVoice(Source source, Parameters parameters) { this.source = source; this.parameters = parameters; }
        public SoundStatus startResult() { return result; }
        public boolean isActive() { return active; }
        public void update(Parameters value) { parameters = value; }
        public void setPaused(boolean value) { paused = value; }
        public void cancel() { cancels++; active = false; }
    }
    static class Pipeline implements RenderPipeline {
        Set<GpuId> supported = Set.of(TYPE);
        boolean dynamic = true;
        SceneSnapshot consumed;
        public boolean requiresDynamicGeometry() { return dynamic; }
        public Set<GpuId> presentationVisualTypes() { return supported; }
        public PipelineDescriptor descriptor() { return PipelineDescriptor.builder(OWNER.child("pipeline"), "Presentation fixture", "1").build(); }
        public void initialize(RenderContext context) { }
        public List<RenderPass> passes() { return List.of(); }
        public void encodeFrame(FrameGraph graph, dev.luxloader.api.frame.FrameContext frame) { }
        public WorldFramePlan worldFramePlan(SceneSnapshot scene, SceneGeometryFeed geometry, dev.luxloader.api.gpu.GpuCommands commands) {
            consumed = scene; return null;
        }
        public void close() { }
    }

    static SceneSnapshot scene() {
        var empty = SceneSnapshot.empty(0);
        return new SceneSnapshot(empty.camera(), List.of(), empty.environment(), Map.of(), 0, true, false,
                0, 0, List.of(), List.of(), List.of(), List.of(), 0, List.of(), List.of());
    }
    static DynamicSceneMesh mesh(View instance) {
        return new DynamicSceneMesh(new CompiledSceneMesh(instance.source().pluginId() + "/" + instance.source().ownerInstance()
                + "/" + instance.source().instanceId(), MeshChunk.Kind.OTHER, (int)instance.parameters().x(), 64, 0, 12,
                List.of(new CompiledSceneMesh.Attribute("POSITION", 0, "RGB32_FLOAT")), new byte[36], new byte[0],
                MeshChunk.IndexType.UNSIGNED_SHORT, 3, CompiledSceneMesh.Topology.TRIANGLES, true),
                new SceneImage("fixture", dev.luxloader.api.gpu.ImageHandle.vkImage(1, "CPU fixture"), dev.luxloader.api.gpu.GpuFormat.R8G8B8A8_UNORM, 1, 1), 0);
    }
    static ClientStateSnapshot snapshot(long sequence, ClientStateSnapshot.LogicalTime time, long world, boolean loaded) {
        return new ClientStateSnapshot(sequence, time, new ClientStateSnapshot.WorldSession(world, loaded, "minecraft:overworld"),
                ClientStateSnapshot.Environment.UNAVAILABLE, ClientStateSnapshot.Player.UNAVAILABLE);
    }
    static void point(PresentationHub hub, Pipeline pipeline, long nanos, boolean paused, long world, boolean loaded, long resources) {
        var time = new ClientStateSnapshot.LogicalTime(nanos / 50_000_000, nanos, paused);
        hub.safePoint(snapshot(1, time, world, loaded), time, world, new ResourceState(resources, ResourceState.Phase.READY), pipeline);
    }
    static Registration register(PresentationHub hub, long owner, GpuId id, Visual visual, SoundAsset sound) {
        return hub.service(owner, OWNER.toString()).register(new Definition(id, visual, sound, Category.PLAYERS, false));
    }
    static Request request(long target, long duration) { return new Request(WORLD, target, duration, PARAMS); }

    @Test void registrationProducesNoSoundGeometryOrStateDemand() {
        var calls = new AtomicInteger(); var port = new SoundPort();
        try (var hub = new PresentationHub(error -> fail(error))) {
            hub.attach(port); register(hub, 1, TYPE, (view, scene) -> { calls.incrementAndGet(); return List.of(mesh(view)); }, SOUND);
            assertFalse(hub.needsState());
            assertTrue(hub.contribute(scene(), new Pipeline() {
                public Set<GpuId> presentationVisualTypes() { fail("No live instance needs a plugin getter"); return Set.of(); }
            }).isEmpty());
            assertTrue(port.voices.isEmpty()); assertEquals(0, calls.get());
        }
    }

    @Test void oneTargetDrivesVisualAndAudioAndUpdatesCoalesceAtTheSameBoundary() {
        var views = new ArrayList<View>(); var port = new SoundPort(); var pipeline = new Pipeline();
        try (var hub = new PresentationHub(error -> fail(error))) {
            hub.attach(port);
            var registration = register(hub, 1, TYPE, (view, scene) -> { views.add(view); return List.of(mesh(view)); }, SOUND);
            var handle = registration.start(request(100, 500));
            point(hub, pipeline, 99, false, 1, true, 1); assertTrue(port.voices.isEmpty());
            point(hub, pipeline, 100, false, 1, true, 1);
            assertEquals(1, port.voices.size()); assertEquals(1, hub.contribute(scene(), pipeline).size());
            assertEquals(handle.snapshot().view().source(), port.voices.getFirst().source);
            assertEquals(0, views.getFirst().elapsedNanos());
            for (int i = 0; i < 1000; i++) handle.update(Parameters.at(i, 64, 2));
            point(hub, pipeline, 200, false, 1, true, 1); hub.contribute(scene(), pipeline);
            assertEquals(999, port.voices.getFirst().parameters.x()); assertEquals(999, views.getLast().parameters().x());
            assertEquals(100, views.getLast().elapsedNanos()); assertEquals(1, hub.metrics().handles());
            point(hub, pipeline, 600, false, 1, true, 1);
            assertEquals(State.COMPLETED, handle.snapshot().state()); assertEquals(1, port.voices.getFirst().cancels);
            assertTrue(hub.contribute(scene(), pipeline).isEmpty()); assertFalse(handle.update(PARAMS));
        }
    }

    @Test void worldAndManualPauseFreezeProgressWithoutRestartingOrCleaningTheOtherInstance() {
        var port = new SoundPort(); var pipeline = new Pipeline();
        try (var hub = new PresentationHub(error -> fail(error))) {
            hub.attach(port); var registration = register(hub, 1, TYPE, (view, scene) -> List.of(mesh(view)), SOUND);
            var first = registration.start(request(0, 1000)); var second = registration.start(request(0, 1000));
            point(hub, pipeline, 100, false, 1, true, 0);
            assertNotEquals(first.snapshot().view().source().instanceId(), second.snapshot().view().source().instanceId());
            point(hub, pipeline, 100, true, 1, true, 0); assertTrue(port.voices.stream().allMatch(voice -> voice.paused));
            first.setPaused(true); point(hub, pipeline, 100, false, 1, true, 0);
            point(hub, pipeline, 400, false, 1, true, 0);
            assertEquals(100, first.snapshot().view().elapsedNanos()); assertEquals(400, second.snapshot().view().elapsedNanos());
            first.setPaused(false); point(hub, pipeline, 400, false, 1, true, 0);
            assertEquals(100, first.snapshot().view().elapsedNanos());
            first.close(); first.close(); assertEquals(1, port.voices.getFirst().cancels);
            assertEquals(0, port.voices.getLast().cancels); assertEquals(1, hub.contribute(scene(), pipeline).size());
        }
    }

    @Test void aFullyMissedLifetimeExpiresAndFutureStartsWaitDuringPause() {
        var port = new SoundPort(); var pipeline = new Pipeline();
        try (var hub = new PresentationHub(error -> fail(error))) {
            hub.attach(port); var registration = register(hub, 1, TYPE, null, SOUND);
            var missed = registration.start(request(0, 10)); point(hub, pipeline, 20, false, 1, true, 0);
            assertEquals(State.COMPLETED, missed.snapshot().state()); assertTrue(port.voices.isEmpty());
            var pending = registration.start(request(20, 100)); point(hub, pipeline, 20, true, 1, true, 0);
            assertEquals(State.QUEUED, pending.snapshot().state()); assertTrue(port.voices.isEmpty());
            point(hub, pipeline, 20, false, 1, true, 0); assertEquals(1, port.voices.size());
        }
    }

    @Test void undeclaredVisualOutputSafelyDegradesAndLegacyDynamicPipelinesRemainCompatible() {
        var calls = new AtomicInteger(); var pipeline = new Pipeline(); pipeline.supported = Set.of();
        try (var hub = new PresentationHub(error -> fail(error))) {
            var registration = register(hub, 1, TYPE, (view, scene) -> { calls.incrementAndGet(); return List.of(mesh(view)); }, SOUND);
            var handle = registration.start(request(0, 100)); point(hub, pipeline, 1, false, 1, true, 0);
            assertEquals(VisualStatus.UNSUPPORTED, handle.snapshot().visual()); assertEquals(SoundStatus.UNSUPPORTED, handle.snapshot().sound());
            assertTrue(hub.contribute(scene(), pipeline).isEmpty()); assertEquals(0, calls.get());
            pipeline.supported = Set.of(TYPE); pipeline.dynamic = false;
            point(hub, pipeline, 2, false, 1, true, 0); assertEquals(VisualStatus.UNSUPPORTED, handle.snapshot().visual());
        }
    }

    @Test void replacementWorldResourceReloadAndPipelineStopCancelVoicesWithoutAnotherFrame() {
        for (int cause = 0; cause < 4; cause++) {
            var port = new SoundPort(); var pipeline = new Pipeline();
            try (var hub = new PresentationHub(error -> fail(error))) {
                hub.attach(port); var handle = register(hub, 1, TYPE, null, SOUND).start(request(0, 100));
                point(hub, pipeline, 1, false, 1, true, 0);
                if (cause == 0) point(hub, pipeline, 2, false, 2, true, 0);
                else if (cause == 1) point(hub, pipeline, 2, false, 1, false, 0);
                else if (cause == 2) point(hub, pipeline, 2, false, 1, true, 1);
                else hub.stopPipeline();
                assertEquals(State.CANCELLED, handle.snapshot().state()); assertEquals(1, port.voices.getFirst().cancels);
                assertEquals(0, hub.metrics().handles());
            }
        }
    }

    @Test void oldOwnerAndRegistrationCannotCancelTheSameIdReplacementOrAnotherOwner() {
        var port = new SoundPort(); var pipeline = new Pipeline(); pipeline.supported = Set.of(TYPE, OWNER.child("other"));
        try (var hub = new PresentationHub(error -> fail(error))) {
            hub.attach(port); var old = register(hub, 1, TYPE, null, SOUND);
            var other = register(hub, 2, OWNER.child("other"), null, SOUND).start(request(0, 100));
            var first = old.start(request(0, 100)); point(hub, pipeline, 1, false, 1, true, 0);
            hub.releaseOwner(1); assertTrue(old.isClosed()); assertEquals(State.CANCELLED, first.snapshot().state());
            var replacement = register(hub, 3, TYPE, null, SOUND).start(request(1, 100));
            old.close(); first.close(); hub.releaseOwner(1); point(hub, pipeline, 2, false, 1, true, 0);
            assertEquals(State.RUNNING, other.snapshot().state()); assertEquals(State.RUNNING, replacement.snapshot().state());
            assertEquals(State.REJECTED, old.start(request(0, 100)).snapshot().state());
            assertEquals(2, hub.metrics().handles());
        }
    }

    @Test void admissionAndUpdatesAreBoundedAndCapacityReturnsAfterCancellation() throws Exception {
        try (var hub = new PresentationHub(error -> fail(error))) {
            var registration = register(hub, 1, TYPE, null, SOUND);
            var handles = new ArrayList<Handle>();
            for (int i = 0; i < PresentationHub.MAX_HANDLES_PER_OWNER; i++) handles.add(registration.start(request(0, 100)));
            assertEquals(Reason.BACKPRESSURE, registration.start(request(0, 100)).snapshot().reason());
            var thread = new Thread(() -> { for (int i = 0; i < 10000; i++) handles.getFirst().update(PARAMS); handles.getFirst().close(); });
            thread.start(); thread.join(5000); assertFalse(thread.isAlive());
            assertEquals(State.QUEUED, registration.start(request(0, 100)).snapshot().state());
            assertEquals(PresentationHub.MAX_HANDLES_PER_OWNER, hub.metrics().handles());
            var custom = new Parameters(0, 0, 0, 1, 1, 1, false, new EventValue.ObjectValue(Map.of("large", new EventValue.Text("x".repeat(40000)))));
            assertThrows(IllegalArgumentException.class, () -> handles.getLast().update(custom));
            assertThrows(IllegalArgumentException.class, () -> hub.service(2, "test:foreign").register(new Definition(TYPE, null, SOUND, Category.PLAYERS, false)));
        }
    }

    @Test void registrationAssetBudgetReturnsAndDoesNotKeepAnUnloadedPluginsBytes() {
        try (var hub = new PresentationHub(error -> fail(error))) {
            byte[] encoded = new byte[SoundAsset.PreparedOgg.MAX_ENCODED_BYTES]; encoded[0] = 'O'; encoded[1] = 'g'; encoded[2] = 'g'; encoded[3] = 'S';
            var asset = new SoundAsset.PreparedOgg(new ResourceKey("test", "tone"), 1, ByteBuffer.wrap(encoded));
            for (int i = 0; i < 8; i++) register(hub, 1, OWNER.child("asset-" + i), null, asset);
            assertThrows(IllegalStateException.class, () -> register(hub, 1, OWNER.child("overflow"), null, asset));
            hub.releaseOwner(1); assertEquals(0, hub.metrics().assetBytes());
            assertDoesNotThrow(() -> register(hub, 2, TYPE, null, asset));
        }
    }

    @Test void soundObservationOverflowResynchronizesAndListenerFailuresDoNotBlockOthers() {
        var failures = new ArrayList<Throwable>(); var batches = new ArrayList<SoundBatch>(); var pipeline = new Pipeline();
        try (var hub = new PresentationHub(failures::add)) {
            hub.attach(new SoundPort());
            hub.service(1, OWNER.toString()).observeHostSounds(batch -> { throw new IllegalStateException("Observer failed"); });
            var observer = hub.service(2, OWNER.toString()).observeHostSounds(batches::add);
            point(hub, pipeline, 0, false, 1, true, 0);
            for (int i = 0; i < PresentationHub.MAX_SOUND_QUEUE + 7; i++) hub.captureHostSound(new HostSound(new ResourceKey("test", "event"), new ResourceKey("test", "sounds/tone.ogg"), PARAMS));
            point(hub, pipeline, 1, false, 1, true, 0);
            assertEquals(1, batches.size()); assertTrue(batches.getFirst().resynchronize()); assertTrue(batches.getFirst().sounds().isEmpty());
            assertEquals(PresentationHub.MAX_SOUND_QUEUE + 7, batches.getFirst().dropped()); assertEquals(1, failures.size());
            observer.close(); hub.releaseOwner(1); assertFalse(hub.needsState());
        }
    }

    @Test void aVisualFailureOrCancellationDuringContributionCannotRemoveHealthyInstances() {
        var pipeline = new Pipeline(); pipeline.supported = Set.of(TYPE, OWNER.child("healthy"));
        var failures = new ArrayList<Throwable>();
        try (var hub = new PresentationHub(failures::add)) {
            var bad = register(hub, 1, TYPE, (view, scene) -> { throw new IllegalStateException("Visual failed"); }, null).start(request(0, 100));
            register(hub, 2, OWNER.child("healthy"), (view, scene) -> List.of(mesh(view)), null).start(request(0, 100));
            point(hub, pipeline, 1, false, 1, true, 0);
            assertEquals(1, hub.contribute(scene(), pipeline).size()); assertEquals(VisualStatus.FAILED, bad.snapshot().visual()); assertEquals(1, failures.size());
            bad.close(); assertEquals(1, hub.contribute(scene(), pipeline).size());
        }
    }

    @Test void pipelineGettersCanRendezvousWithAWorkerReadingThePublicService() throws Exception {
        try (var hub = new PresentationHub(error -> fail(error))) {
            var service = hub.service(1, OWNER.toString());
            var calls = new AtomicInteger();
            var pipeline = new Pipeline() {
                public Set<GpuId> presentationVisualTypes() {
                    var queried = new java.util.concurrent.CountDownLatch(1);
                    var worker = new Thread(() -> { service.capabilities(); queried.countDown(); });
                    worker.start();
                    try {
                        assertTrue(queried.await(2, java.util.concurrent.TimeUnit.SECONDS), "A plugin getter must not hold the ledger lock");
                        worker.join(2000); assertFalse(worker.isAlive());
                    } catch (InterruptedException e) { throw new AssertionError(e); }
                    calls.incrementAndGet(); return Set.of(TYPE);
                }
            };
            hub.pipelineActivated(pipeline);
            register(hub, 1, TYPE, (view, scene) -> List.of(mesh(view)), null).start(request(0, 100));
            point(hub, pipeline, 1, false, 1, true, 0);
            assertEquals(1, hub.contribute(scene(), pipeline).size()); assertEquals(3, calls.get());
        }
    }

    @Test void aBlockingHostStartDoesNotBlockCancellationAndItsLateVoiceCannotEnterAReplacementOwner() throws Exception {
        try (var hub = new PresentationHub(error -> fail(error)); var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var cancelled = new java.util.concurrent.CountDownLatch(1);
            var port = new SoundPort() {
                public Voice start(Source source, SoundAsset asset, Category category, boolean loop, Parameters parameters) {
                    var result = super.start(source, asset, category, loop, parameters);
                    if (source.ownerInstance() == 1) {
                        entered.countDown();
                        try { assertTrue(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS), "Cancel must run while the host start is waiting"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    }
                    return result;
                }
            };
            hub.attach(port); var old = register(hub, 1, TYPE, null, SOUND);
            var handle = old.start(request(0, 100));
            var replacement = new java.util.concurrent.atomic.AtomicReference<Handle>();
            var cancellation = worker.submit(() -> {
                try { assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                assertEquals(State.RUNNING, handle.snapshot().state());
                assertTrue(handle.update(Parameters.at(5, 64, 0)));
                hub.releaseOwner(1);
                replacement.set(register(hub, 2, TYPE, null, SOUND).start(request(0, 100)));
                cancelled.countDown();
            });
            point(hub, new Pipeline(), 1, false, 1, true, 0);
            cancellation.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(State.CANCELLED, handle.snapshot().state()); assertEquals(1, port.voices.getFirst().cancels);
            point(hub, new Pipeline(), 2, false, 1, true, 0);
            assertEquals(State.RUNNING, replacement.get().snapshot().state()); assertEquals(0, port.voices.getLast().cancels);
            assertEquals(1, hub.metrics().handles());
        }
    }

    @Test void aDelayedEnableCannotReviveObservationAfterTheLastSubscriptionCloses() throws Exception {
        try (var hub = new PresentationHub(error -> fail(error)); var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var entered = new java.util.concurrent.CountDownLatch(1); var disabled = new java.util.concurrent.CountDownLatch(1);
            class OrderedPort extends SoundPort {
                long revision = -1; boolean enabled;
                public void observe(long generation, boolean value, java.util.function.Consumer<HostSound> sink) {
                    if (value) {
                        entered.countDown();
                        try { assertTrue(disabled.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    }
                    synchronized (this) { if (generation >= revision) { revision = generation; enabled = value; } }
                }
            }
            var port = new OrderedPort(); hub.attach(port);
            var subscription = hub.service(1, OWNER.toString()).observeHostSounds(batch -> fail("Closed listener"));
            var cancellation = worker.submit(() -> {
                try { assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                subscription.close(); disabled.countDown();
            });
            point(hub, new Pipeline(), 1, false, 1, true, 0); cancellation.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(port.enabled); assertFalse(hub.needsState()); assertFalse(hub.hasHostSoundObservers());
            hub.captureHostSound(new HostSound(new ResourceKey("test", "event"), new ResourceKey("test", "sounds/tone.ogg"), PARAMS));
            assertEquals(0, hub.metrics().soundQueued());
        }
    }

    @Test void disablingFromAnObservationCallbackRejectsNewOutputAtThatSameBoundary() {
        try (var hub = new PresentationHub(error -> fail(error))) {
            var port = new SoundPort(); hub.attach(port); var registration = register(hub, 1, TYPE, null, SOUND);
            var requested = new ArrayList<Handle>();
            hub.service(1, OWNER.toString()).observeHostSounds(batch -> { hub.stopPipeline(); requested.add(registration.start(request(0, 100))); });
            point(hub, new Pipeline(), 1, false, 1, true, 0);
            hub.captureHostSound(new HostSound(new ResourceKey("test", "event"), new ResourceKey("test", "sounds/tone.ogg"), PARAMS));
            point(hub, new Pipeline(), 2, false, 1, true, 0);
            assertEquals(1, requested.size()); assertEquals(Reason.PIPELINE_STOPPED, requested.getFirst().snapshot().reason());
            assertTrue(port.voices.isEmpty());
        }
    }

    @Test void cancellationInsideAVisualCallbackRejectsItsLateMeshesAndPreservesAnotherOwner() {
        try (var hub = new PresentationHub(error -> fail(error))) {
            var pipeline = new Pipeline(); pipeline.supported = Set.of(TYPE, OWNER.child("healthy"));
            var cancelled = register(hub, 1, TYPE, (view, scene) -> { hub.releaseOwner(1); return List.of(mesh(view)); }, null).start(request(0, 100));
            var healthy = register(hub, 2, OWNER.child("healthy"), (view, scene) -> List.of(mesh(view)), null).start(request(0, 100));
            point(hub, pipeline, 1, false, 1, true, 0);
            var meshes = hub.contribute(scene(), pipeline);
            assertEquals(1, meshes.size()); assertEquals(State.CANCELLED, cancelled.snapshot().state());
            assertEquals(VisualStatus.CONTRIBUTED, healthy.snapshot().visual()); assertEquals(1, hub.metrics().handles());
        }
    }

    @Test void exactHostRequestResultsAndLateDecodeFailureRemainDistinctFromAudibility() {
        for (var result : List.of(SoundStatus.HOST_STARTED_SILENTLY, SoundStatus.HOST_NOT_STARTED, SoundStatus.UNKNOWN)) {
            try (var hub = new PresentationHub(error -> fail(error))) {
                var port = new SoundPort() {
                    public Voice start(Source source, SoundAsset asset, Category category, boolean loop, Parameters parameters) {
                        var voice = (SoundVoice)super.start(source, asset, category, loop, parameters); voice.result = result;
                        if (result == SoundStatus.UNKNOWN) voice.active = false;
                        return voice;
                    }
                };
                hub.attach(port); var handle = register(hub, 1, TYPE, null, SOUND).start(request(0, 100));
                point(hub, new Pipeline(), 1, false, 1, true, 0);
                assertEquals(result, handle.snapshot().sound()); assertEquals(State.RUNNING, handle.snapshot().state());
                assertEquals(result == SoundStatus.HOST_NOT_STARTED ? 1 : 0, port.voices.getFirst().cancels);
                if (result != SoundStatus.HOST_NOT_STARTED) {
                    port.voices.getFirst().result = SoundStatus.FAILED;
                    point(hub, new Pipeline(), 2, false, 1, true, 0);
                    assertEquals(SoundStatus.FAILED, handle.snapshot().sound()); assertEquals(1, port.voices.getFirst().cancels);
                }
            }
        }
    }
}
