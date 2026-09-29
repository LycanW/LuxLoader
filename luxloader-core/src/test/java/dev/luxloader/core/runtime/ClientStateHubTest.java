package dev.luxloader.core.runtime;

import dev.luxloader.api.state.ClientStateBatch;
import dev.luxloader.api.state.ClientStateSnapshot;
import dev.luxloader.api.state.ClientStateSubscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientStateHubTest {

    @Test
    @DisplayName("No subscribers skip all state payload construction")
    void noSubscribersSkipPayloadConstruction() {
        AtomicLong now = new AtomicLong();
        ClientStateHub hub = new ClientStateHub(now::get, 4, ignored -> { });
        AtomicInteger factoryCalls = new AtomicInteger();
        var factory = factory(factoryCalls, 1L, true, false);

        hub.clientTick(false, factory);
        hub.safePoint(false, 1L, factory);
        hub.clientTick(false, factory);

        assertEquals(0, factoryCalls.get());
        assertEquals(0, hub.metrics().pendingSamples());
        assertTrue(hub.current().isEmpty());
    }

    @Test
    @DisplayName("A midstream subscriber starts from its first safe boundary")
    void midstreamSubscriptionGetsBoundaryBaseline() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> { });
        AtomicInteger noSubscriberPayloads = new AtomicInteger();
        hub.clientTick(false, factory(noSubscriberPayloads, 4L, true, true));
        hub.safePoint(false, 4L, factory(noSubscriberPayloads, 4L, true, true));
        assertEquals(0, noSubscriberPayloads.get());

        List<ClientStateBatch> batches = new ArrayList<>();
        hub.serviceForOwner(10L).subscribe(batches::add);
        hub.safePoint(false, 4L, factory(new AtomicInteger(), 4L, true, true));

        assertEquals(1, batches.size());
        assertTrue(batches.getFirst().initial());
        assertEquals(4L, batches.getFirst().snapshot().world().generation());
        assertTrue(batches.getFirst().snapshot().player().sprinting());
        assertTrue(batches.getFirst().sprintTransitions().isEmpty());
    }

    @Test
    @DisplayName("A subscriber without a world still receives an unavailable baseline")
    void noWorldProducesUnavailableBaseline() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> { });
        List<ClientStateBatch> batches = new ArrayList<>();
        hub.serviceForOwner(1L).subscribe(batches::add);

        hub.safePoint(false, 0L, factory(factoryCounter(), 0L, false, false));

        assertEquals(1, batches.size());
        assertTrue(batches.getFirst().initial());
        assertFalse(batches.getFirst().snapshot().world().loaded());
        assertFalse(batches.getFirst().snapshot().environment().valid());
        assertFalse(batches.getFirst().snapshot().player().valid());
    }

    @Test
    @DisplayName("A session change before first delivery replaces the cached baseline")
    void initialBaselineUsesCurrentSessionAfterPreDeliveryWorldChange() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> { });
        List<ClientStateBatch> existingBatches = new ArrayList<>();
        hub.serviceForOwner(1L).subscribe(existingBatches::add);
        var generationOne = factory(factoryCounter(), 1L, true, false);
        hub.safePoint(false, 1L, generationOne);

        List<ClientStateBatch> newSubscriberBatches = new ArrayList<>();
        hub.serviceForOwner(2L).subscribe(newSubscriberBatches::add);
        hub.safePoint(false, 2L, factory(factoryCounter(), 2L, true, true));

        assertEquals(1, newSubscriberBatches.size());
        ClientStateBatch initial = newSubscriberBatches.getFirst();
        assertTrue(initial.initial());
        assertEquals(2L, initial.snapshot().world().generation());
        assertEquals(ClientStateBatch.ResyncReason.NONE, initial.resyncReason());
        assertTrue(initial.sprintTransitions().isEmpty());
        assertEquals(ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED,
                existingBatches.getLast().resyncReason());
    }

    @Test
    @DisplayName("A stale sample cannot satisfy a new-session initial baseline")
    void initialBaselineWaitsForCurrentSessionSample() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> { });
        List<ClientStateBatch> existingBatches = new ArrayList<>();
        hub.serviceForOwner(1L).subscribe(existingBatches::add);
        hub.safePoint(false, 1L, factory(factoryCounter(), 1L, true, false));

        List<ClientStateBatch> newSubscriberBatches = new ArrayList<>();
        hub.serviceForOwner(2L).subscribe(newSubscriberBatches::add);
        hub.safePoint(false, 2L, factory(factoryCounter(), 1L, true, false));
        assertTrue(newSubscriberBatches.isEmpty());
        assertTrue(hub.current().isEmpty());

        hub.safePoint(false, 2L, factory(factoryCounter(), 2L, true, true));

        assertEquals(1, newSubscriberBatches.size());
        ClientStateBatch initial = newSubscriberBatches.getFirst();
        assertTrue(initial.initial());
        assertEquals(2L, initial.snapshot().world().generation());
        assertTrue(initial.sprintTransitions().isEmpty());
        assertEquals(ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED,
                existingBatches.getLast().resyncReason());
    }

    @Test
    @DisplayName("Overflow before first delivery uses the newest sample as a fresh initial baseline")
    void initialBaselineResetsAtOverflowWithoutGapSpanningSprintTransition() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 2, ignored -> { });
        List<ClientStateBatch> existingBatches = new ArrayList<>();
        hub.serviceForOwner(1L).subscribe(existingBatches::add);
        hub.safePoint(false, 1L, factory(factoryCounter(), 1L, true, false));

        List<ClientStateBatch> newSubscriberBatches = new ArrayList<>();
        hub.serviceForOwner(2L).subscribe(newSubscriberBatches::add);
        var sprinting = factory(factoryCounter(), 1L, true, true);
        for (int i = 0; i < 5; i++) hub.clientTick(false, sprinting);
        hub.safePoint(false, 1L, sprinting);

        assertEquals(1, newSubscriberBatches.size());
        ClientStateBatch initial = newSubscriberBatches.getFirst();
        assertTrue(initial.initial());
        assertEquals(6L, initial.snapshot().sampleSequence());
        assertTrue(initial.snapshot().player().sprinting());
        assertEquals(ClientStateBatch.ResyncReason.NONE, initial.resyncReason());
        assertEquals(0L, initial.droppedSamples());
        assertTrue(initial.sprintTransitions().isEmpty());

        ClientStateBatch existingResync = existingBatches.getLast();
        assertEquals(ClientStateBatch.ResyncReason.QUEUE_OVERFLOW, existingResync.resyncReason());
        assertTrue(existingResync.droppedSamples() > 0L);
        assertTrue(existingResync.sprintTransitions().isEmpty());
    }

    @Test
    @DisplayName("A failed capture before first delivery waits for recovery instead of replaying cached state")
    void initialBaselineWaitsForSampleRecovery() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> { });
        List<ClientStateBatch> existingBatches = new ArrayList<>();
        hub.serviceForOwner(1L).subscribe(existingBatches::add);
        hub.safePoint(false, 1L, factory(factoryCounter(), 1L, true, false));

        List<ClientStateBatch> newSubscriberBatches = new ArrayList<>();
        hub.serviceForOwner(2L).subscribe(newSubscriberBatches::add);
        var failedSample = (java.util.function.BiFunction<Long, ClientStateSnapshot.LogicalTime,
                ClientStateSnapshot>) (sequence, time) -> {
            throw new IllegalStateException("source is unavailable");
        };
        hub.clientTick(false, failedSample);
        hub.safePoint(false, 1L, (sequence, time) -> null);

        assertTrue(hub.current().isEmpty());
        assertTrue(newSubscriberBatches.isEmpty(), "Do not publish the cached snapshot while capture is unavailable");

        hub.safePoint(false, 1L, factory(factoryCounter(), 1L, true, true));

        assertEquals(1, newSubscriberBatches.size());
        ClientStateBatch recoveredInitial = newSubscriberBatches.getFirst();
        assertTrue(recoveredInitial.initial());
        assertEquals(4L, recoveredInitial.snapshot().sampleSequence());
        assertTrue(recoveredInitial.snapshot().player().sprinting());
        assertEquals(ClientStateBatch.ResyncReason.NONE, recoveredInitial.resyncReason());
        assertTrue(recoveredInitial.sprintTransitions().isEmpty());
        ClientStateBatch existingResync = existingBatches.getLast();
        assertEquals(ClientStateBatch.ResyncReason.SAMPLE_CAPTURE_FAILED, existingResync.resyncReason());
        assertTrue(existingResync.droppedSamples() > 0L);
        assertTrue(existingResync.sprintTransitions().isEmpty());
    }

    @Test
    @DisplayName("A continuous cached baseline still delivers later sprint changes")
    void cachedInitialBaselinePreservesContinuousUpdates() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> { });
        hub.serviceForOwner(1L).subscribe(ignored -> { });
        hub.safePoint(false, 1L, factory(factoryCounter(), 1L, true, false));

        List<ClientStateBatch> newSubscriberBatches = new ArrayList<>();
        hub.serviceForOwner(2L).subscribe(newSubscriberBatches::add);
        var sprinting = factory(factoryCounter(), 1L, true, true);
        hub.clientTick(false, sprinting);
        hub.safePoint(false, 1L, sprinting);

        assertEquals(2, newSubscriberBatches.size());
        assertTrue(newSubscriberBatches.getFirst().initial());
        assertEquals(1L, newSubscriberBatches.getFirst().snapshot().sampleSequence());
        assertFalse(newSubscriberBatches.getFirst().snapshot().player().sprinting());
        assertFalse(newSubscriberBatches.get(1).initial());
        assertEquals(1, newSubscriberBatches.get(1).sprintTransitions().size());
        assertTrue(newSubscriberBatches.get(1).sprintTransitions().getFirst().isSprinting());
    }

    @Test
    @DisplayName("Respawn with the same entity ID and UUID does not create a sprint transition")
    void reusedEntityIdentityFieldsDoNotBridgePlayerLifetimes() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> { });
        List<ClientStateBatch> batches = new ArrayList<>();
        hub.serviceForOwner(1L).subscribe(batches::add);
        AtomicInteger playerGeneration = new AtomicInteger(4);
        AtomicInteger state = new AtomicInteger();
        var factory = (java.util.function.BiFunction<Long, ClientStateSnapshot.LogicalTime,
                ClientStateSnapshot>) (sequence, time) -> snapshot(sequence, time, 1L, true,
                state.get() > 0, 7, "same-uuid", playerGeneration.get());

        hub.safePoint(false, 1L, factory);
        state.incrementAndGet();
        playerGeneration.incrementAndGet();
        hub.clientTick(false, factory);
        hub.safePoint(false, 1L, factory);

        assertEquals(2, batches.size());
        assertTrue(batches.get(1).sprintTransitions().isEmpty());
        assertEquals(7, batches.get(1).snapshot().player().identity().entityId());
        assertEquals("same-uuid", batches.get(1).snapshot().player().identity().uuid());
        assertEquals(5L, batches.get(1).snapshot().player().identity().generation());
    }

    @Test
    @DisplayName("Sprint changes are client-observed and repeated render safe points add no event")
    void sprintStateDifferenceAndRepeatedSafePoint() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> { });
        List<ClientStateBatch> batches = new ArrayList<>();
        hub.serviceForOwner(1L).subscribe(batches::add);
        var factory = factory(new AtomicInteger(), 1L, true, false);

        hub.safePoint(false, 1L, factory);
        hub.clientTick(false, factory(factoryCounter(), 1L, true, true));
        hub.safePoint(false, 1L, factory(factoryCounter(), 1L, true, true));
        int countAfterTransition = batches.size();
        hub.safePoint(false, 1L, factory(factoryCounter(), 1L, true, true));

        assertEquals(2, countAfterTransition);
        assertEquals(countAfterTransition, batches.size(), "A render boundary without a new tick has no duplicate batch");
        ClientStateBatch.SprintTransition transition = batches.get(1).sprintTransitions().getFirst();
        assertFalse(transition.wasSprinting());
        assertTrue(transition.isSprinting());
        assertEquals(ClientStateBatch.SprintTransition.Source.CLIENT_OBSERVED_STATE_DIFFERENCE,
                transition.source());
        assertEquals(transition.sequence(), batches.get(1).snapshot().sampleSequence());
    }

    @Test
    @DisplayName("Logical time freezes during pause and resumes from the monotonic clock")
    void logicalTimeFreezesWhilePaused() {
        AtomicLong now = new AtomicLong();
        ClientStateHub hub = new ClientStateHub(now::get, 16, ignored -> { });
        List<ClientStateBatch> batches = new ArrayList<>();
        hub.serviceForOwner(1L).subscribe(batches::add);
        var factory = factory(new AtomicInteger(), 1L, true, false);

        hub.safePoint(false, 1L, factory);
        now.addAndGet(50_000_000L);
        hub.clientTick(false, factory);
        hub.safePoint(false, 1L, factory);
        assertEquals(1L, batches.getLast().snapshot().time().tick());
        assertEquals(50_000_000L, batches.getLast().snapshot().time().elapsedNanos());

        now.addAndGet(500_000_000L);
        hub.safePoint(true, 1L, factory);
        ClientStateSnapshot.LogicalTime paused = batches.getLast().snapshot().time();
        assertTrue(paused.paused());
        assertEquals(50_000_000L, paused.elapsedNanos());
        int pausedBatchCount = batches.size();
        now.addAndGet(2_000_000_000L);
        hub.safePoint(true, 1L, factory);
        assertEquals(pausedBatchCount, batches.size());

        now.addAndGet(25_000_000L);
        hub.safePoint(false, 1L, factory);
        now.addAndGet(25_000_000L);
        hub.clientTick(false, factory);
        hub.safePoint(false, 1L, factory);
        assertEquals(2L, batches.getLast().snapshot().time().tick());
        assertEquals(75_000_000L, batches.getLast().snapshot().time().elapsedNanos());
        assertFalse(batches.getLast().snapshot().time().paused());
    }

    @Test
    @DisplayName("Queue overflow reports a gap and resynchronizes from the newest snapshot")
    void overflowProducesBoundedResynchronization() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 3, ignored -> { });
        List<ClientStateBatch> batches = new ArrayList<>();
        hub.serviceForOwner(1L).subscribe(batches::add);
        var state = new AtomicInteger();
        var factory = (java.util.function.BiFunction<Long, ClientStateSnapshot.LogicalTime,
                ClientStateSnapshot>) (sequence, time) -> snapshot(sequence, time, 1L, true,
                state.get() % 2 == 0, 7, "same-player", 1L);

        hub.safePoint(false, 1L, factory);
        for (int i = 0; i < 7; i++) {
            state.incrementAndGet();
            hub.clientTick(false, factory);
        }
        assertTrue(hub.metrics().pendingSamples() <= 3);
        hub.safePoint(false, 1L, factory);

        ClientStateBatch resync = batches.getLast();
        assertEquals(ClientStateBatch.ResyncReason.QUEUE_OVERFLOW, resync.resyncReason());
        assertFalse(resync.initial());
        assertTrue(resync.sprintTransitions().isEmpty());
        assertTrue(resync.droppedSamples() >= 4L);
        assertTrue(hub.metrics().queueOverflowCount() > 0L);
        assertTrue(hub.metrics().queuePeak() <= 3L);
        assertTrue(resync.snapshot().sampleSequence() >= resync.lastSequence());
    }

    @Test
    @DisplayName("World replacement resynchronizes and rejects a late old-session sample")
    void sessionReplacementRejectsLateSamples() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> { });
        List<ClientStateBatch> batches = new ArrayList<>();
        hub.serviceForOwner(1L).subscribe(batches::add);
        var factory = factory(new AtomicInteger(), 1L, true, false);
        hub.safePoint(false, 1L, factory);

        hub.clientTick(false, factory(factoryCounter(), 2L, true, true));
        hub.safePoint(false, 2L, factory(factoryCounter(), 2L, true, true));
        assertEquals(ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED, batches.getLast().resyncReason());
        int afterReplacement = batches.size();

        hub.clientTick(false, factory(factoryCounter(), 1L, true, false));
        hub.safePoint(false, 2L, factory(factoryCounter(), 2L, true, true));

        assertEquals(afterReplacement, batches.size());
        assertTrue(hub.metrics().staleSamplesRejected() > 0L);
        assertEquals(2L, hub.current().orElseThrow().world().generation());
    }

    @Test
    @DisplayName("Cancellation is idempotent and owner cleanup cannot cancel a replacement")
    void ownerCancellationIsInstanceScoped() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> { });
        List<ClientStateBatch> oldBatches = new ArrayList<>();
        ClientStateSubscription oldSubscription = hub.serviceForOwner(100L).subscribe(oldBatches::add);
        var factory = factory(factoryCounter(), 1L, true, false);
        hub.safePoint(false, 1L, factory);
        assertEquals(1, oldBatches.size());

        hub.releaseOwner(100L);
        assertTrue(oldSubscription.isCancelled());
        assertFalse(oldSubscription.cancel());
        List<ClientStateBatch> newBatches = new ArrayList<>();
        ClientStateSubscription replacement = hub.serviceForOwner(101L).subscribe(newBatches::add);
        hub.safePoint(false, 1L, factory);
        assertEquals(1, newBatches.size());
        assertFalse(replacement.isCancelled());
        assertFalse(oldSubscription.cancel());
        assertFalse(replacement.isCancelled());
    }

    @Test
    @DisplayName("A failing listener is cancelled without blocking other subscribers")
    void listenerFailureIsIsolated() {
        AtomicInteger failures = new AtomicInteger();
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> failures.incrementAndGet());
        ClientStateSubscription failing = hub.serviceForOwner(1L).subscribe(batch -> {
            throw new IllegalStateException("listener failed");
        });
        List<ClientStateBatch> healthyBatches = new ArrayList<>();
        hub.serviceForOwner(2L).subscribe(healthyBatches::add);
        hub.safePoint(false, 1L, factory(factoryCounter(), 1L, true, false));

        assertTrue(failing.isCancelled());
        assertEquals(1, failures.get());
        assertEquals(1, healthyBatches.size());
        assertTrue(healthyBatches.getFirst().initial());
    }

    @Test
    @DisplayName("A failed source capture clears stale state and resynchronizes on recovery")
    void captureFailureResynchronizesOnRecovery() {
        ClientStateHub hub = new ClientStateHub(() -> 0L, 8, ignored -> { });
        List<ClientStateBatch> batches = new ArrayList<>();
        hub.serviceForOwner(1L).subscribe(batches::add);
        var good = factory(factoryCounter(), 1L, true, false);
        hub.safePoint(false, 1L, good);
        assertEquals(1, batches.size());

        hub.clientTick(false, (sequence, time) -> {
            throw new IllegalStateException("sample unavailable");
        });
        assertTrue(hub.current().isEmpty(), "A failed source must not leave a stale current snapshot");
        hub.safePoint(false, 1L, good);

        ClientStateBatch recovered = batches.getLast();
        assertEquals(ClientStateBatch.ResyncReason.SAMPLE_CAPTURE_FAILED, recovered.resyncReason());
        assertTrue(recovered.sprintTransitions().isEmpty());
        assertTrue(recovered.droppedSamples() > 0L);
        assertTrue(hub.current().isPresent());
    }

    private static AtomicInteger factoryCounter() {
        return new AtomicInteger();
    }

    private static java.util.function.BiFunction<Long, ClientStateSnapshot.LogicalTime, ClientStateSnapshot> factory(
            AtomicInteger calls, long generation, boolean loaded, boolean sprinting) {
        return (sequence, time) -> {
            calls.incrementAndGet();
            return snapshot(sequence, time, generation, loaded, sprinting, 7, "test-player", 1L);
        };
    }

    private static ClientStateSnapshot snapshot(long sequence, ClientStateSnapshot.LogicalTime time,
            long sessionGeneration, boolean loaded, boolean sprinting, int entityId, String uuid,
            long playerGeneration) {
        var world = new ClientStateSnapshot.WorldSession(sessionGeneration, loaded,
                loaded ? "minecraft:overworld" : "");
        var environment = loaded
                ? new ClientStateSnapshot.Environment(true, "minecraft:overworld", 6000L, 0f, 0f,
                        new ClientStateSnapshot.BiomeSample(true, "minecraft:plains", 1, 64, 2))
                : ClientStateSnapshot.Environment.UNAVAILABLE;
        var identity = new ClientStateSnapshot.EntityIdentity(sessionGeneration, entityId, uuid, playerGeneration);
        var player = loaded
                ? new ClientStateSnapshot.Player(true, identity, 1, 64, 2, 0, 0, 0, 0, 0,
                        true, false, sprinting, false, false, ClientStateSnapshot.Pose.STANDING,
                        true, 20, 20, true, 0, ClientStateSnapshot.ItemDescription.EMPTY,
                        ClientStateSnapshot.ItemDescription.EMPTY)
                : ClientStateSnapshot.Player.UNAVAILABLE;
        return new ClientStateSnapshot(sequence, time, world, environment, player);
    }
}
