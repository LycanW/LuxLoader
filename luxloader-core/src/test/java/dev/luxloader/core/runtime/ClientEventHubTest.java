package dev.luxloader.core.runtime;

import dev.luxloader.api.event.BehaviorEvent;
import dev.luxloader.api.event.ClientEventBatch;
import dev.luxloader.api.event.CustomEvent;
import dev.luxloader.api.event.CustomEventType;
import dev.luxloader.api.event.EventTypeId;
import dev.luxloader.api.event.EventValue;
import dev.luxloader.api.state.ClientStateBatch;
import dev.luxloader.api.state.ClientStateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientEventHubTest {
    @Test
    @DisplayName("No subscribers skip built-in payload construction")
    void noSubscribersSkipPayloadConstruction() {
        ClientEventHub hub = new ClientEventHub(2, ignored -> { });
        AtomicInteger fieldsBuilt = new AtomicInteger();
        ClientStateSnapshot state = state(1L, 1L);

        assertFalse(hub.recordBehavior(1L, BehaviorEvent.Kind.ATTACK, BehaviorEvent.Phase.REQUESTED,
                BehaviorEvent.Source.CLIENT_PREDICTION, () -> {
                    fieldsBuilt.incrementAndGet();
                    return fields("targetEntityId", 4L);
                }, Optional.of(state)));

        assertEquals(0, fieldsBuilt.get());
        assertEquals(0, hub.metrics().pendingEvents());
    }

    @Test
    @DisplayName("Events are delivered only at a safe point and preserve order after an initial baseline")
    void safePointDeliveryPreservesEventsPublishedBeforeFirstBaseline() {
        ClientEventHub hub = new ClientEventHub(8, ignored -> { });
        List<ClientEventBatch> batches = new ArrayList<>();
        hub.serviceForOwner(1L, "example:listener", Optional::empty).subscribe(batches::add);
        ClientStateSnapshot first = state(1L, 4L);

        assertTrue(hub.recordBehavior(4L, BehaviorEvent.Kind.ATTACK, BehaviorEvent.Phase.REQUESTED,
                BehaviorEvent.Source.CLIENT_PREDICTION,
                () -> fields("targetEntityId", 9L), Optional.of(first)));
        assertTrue(batches.isEmpty());
        hub.safePoint(first);

        assertEquals(2, batches.size());
        assertTrue(batches.getFirst().initial());
        assertTrue(batches.getLast().events().getFirst() instanceof BehaviorEvent);
        BehaviorEvent event = (BehaviorEvent) batches.getLast().events().getFirst();
        assertEquals(1L, event.stamp().sequence());
        assertEquals(4L, event.stamp().sessionGeneration());
        assertEquals(1L, event.stamp().logicalTime().tick());
        assertEquals(new EventValue.IntegerNumber(9L), event.fields().values().get("targetEntityId"));
    }

    @Test
    @DisplayName("An event captured before the first state sample is stamped at the first safe point")
    void firstEventWaitsForInitialSnapshot() {
        ClientEventHub hub = new ClientEventHub(8, ignored -> { });
        List<ClientEventBatch> batches = new ArrayList<>();
        hub.serviceForOwner(1L, "example:listener", Optional::empty).subscribe(batches::add);

        assertTrue(hub.recordBehavior(7L, BehaviorEvent.Kind.HEALTH_UPDATE,
                BehaviorEvent.Phase.SERVER_NOTIFIED, BehaviorEvent.Source.SERVER_NOTIFICATION,
                () -> fields("health", 18L), Optional.empty()));
        assertTrue(batches.isEmpty());

        ClientStateSnapshot first = state(1L, 7L);
        hub.safePoint(first);
        assertEquals(2, batches.size());
        BehaviorEvent event = (BehaviorEvent) batches.getLast().events().getFirst();
        assertEquals(7L, event.stamp().sessionGeneration());
        assertEquals(first.time(), event.stamp().logicalTime());
    }

    @Test
    @DisplayName("Queue overflow emits one resynchronization and accepts later events")
    void overflowResynchronizesAndRecovers() {
        ClientEventHub hub = new ClientEventHub(2, ignored -> { });
        List<ClientEventBatch> batches = new ArrayList<>();
        hub.serviceForOwner(1L, "example:listener", Optional::empty).subscribe(batches::add);
        ClientStateSnapshot first = state(1L, 1L);
        hub.safePoint(first);
        batches.clear();

        assertTrue(record(hub, first, 1L));
        assertTrue(record(hub, first, 1L));
        assertFalse(record(hub, first, 1L));
        assertEquals(1L, hub.metrics().queueOverflowCount());
        assertTrue(hub.metrics().blockedUntilSafePoint());

        ClientStateSnapshot second = state(2L, 1L);
        hub.safePoint(second);
        assertEquals(1, batches.size());
        assertEquals(ClientStateBatch.ResyncReason.QUEUE_OVERFLOW, batches.getFirst().resyncReason());
        assertTrue(batches.getFirst().events().isEmpty());
        assertEquals(3L, batches.getFirst().droppedEvents());

        assertTrue(record(hub, second, 1L));
        hub.safePoint(state(3L, 1L));
        assertEquals(2, batches.size());
        assertEquals(1, batches.getLast().events().size());
        assertEquals(ClientStateBatch.ResyncReason.NONE, batches.getLast().resyncReason());
    }

    @Test
    @DisplayName("World-session changes discard old events and reject late old-session capture")
    void sessionChangeResynchronizesAndRejectsLateCapture() {
        ClientEventHub hub = new ClientEventHub(8, ignored -> { });
        List<ClientEventBatch> batches = new ArrayList<>();
        hub.serviceForOwner(1L, "example:listener", Optional::empty).subscribe(batches::add);
        ClientStateSnapshot first = state(1L, 1L);
        hub.safePoint(first);
        batches.clear();
        assertTrue(record(hub, first, 1L));

        ClientStateSnapshot nextWorld = state(2L, 2L);
        hub.safePoint(nextWorld);
        assertEquals(1, batches.size());
        assertEquals(ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED, batches.getFirst().resyncReason());
        assertTrue(batches.getFirst().events().isEmpty());
        assertEquals(2L, batches.getFirst().snapshot().world().generation());
        assertFalse(record(hub, nextWorld, 1L));
        assertEquals(1L, hub.metrics().staleEventsRejected());
    }

    @Test
    @DisplayName("Custom event types enforce namespace and instance ownership through cancellation")
    void customEventOwnershipPublicationAndCancellation() {
        ClientEventHub hub = new ClientEventHub(8, ignored -> { });
        AtomicReference<ClientStateSnapshot> latest = new AtomicReference<>(state(1L, 1L));
        var writer = hub.serviceForOwner(10L, "example:writer", () -> Optional.ofNullable(latest.get()));
        var reader = hub.serviceForOwner(20L, "example:reader", () -> Optional.ofNullable(latest.get()));
        CustomEventType type = writer.registerCustomType(new EventTypeId("example", "writer/pulse"), 2).type();
        List<ClientEventBatch> customBatches = new ArrayList<>();
        List<CustomEvent> received = new ArrayList<>();
        var subscription = reader.subscribeCustom(type, batch -> {
            customBatches.add(batch);
            batch.events().stream().filter(CustomEvent.class::isInstance)
                    .map(CustomEvent.class::cast).forEach(received::add);
        });

        assertThrows(IllegalArgumentException.class,
                () -> reader.publish(type, fields("value", 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> writer.registerCustomType(new EventTypeId("other", "writer/pulse"), 1));

        hub.safePoint(latest.get());
        assertTrue(customBatches.getFirst().initial());
        EventValue.ObjectValue payload = new EventValue.ObjectValue(Map.of("count", new EventValue.IntegerNumber(3L)));
        writer.publish(type, payload);
        assertTrue(received.isEmpty());
        latest.set(state(2L, 1L));
        hub.safePoint(latest.get());

        assertEquals(1, received.size());
        assertEquals(type, received.getFirst().type());
        assertEquals(new EventValue.IntegerNumber(3L), received.getFirst().payload().values().get("count"));
        hub.releaseOwner(10L);
        assertThrows(IllegalArgumentException.class, () -> writer.publish(type, payload));
        assertTrue(subscription.cancel());
        assertTrue(subscription.isCancelled());
        assertEquals(0, hub.metrics().activeSubscriptions());
        assertEquals(0, hub.metrics().customTypes());
    }

    @Test
    @DisplayName("Type-filtered custom listeners retain queue resynchronization batches")
    void customSubscriptionCanObserveQueueGap() {
        ClientEventHub hub = new ClientEventHub(1, ignored -> { });
        ClientStateSnapshot state = state(1L, 1L);
        var writer = hub.serviceForOwner(1L, "example:writer", () -> Optional.of(state));
        CustomEventType type = writer.registerCustomType(new EventTypeId("example", "writer/pulse"), 1).type();
        List<ClientEventBatch> batches = new ArrayList<>();
        hub.serviceForOwner(2L, "example:reader", () -> Optional.of(state))
                .subscribeCustom(type, batches::add);
        hub.safePoint(state);
        writer.publish(type, fields("value", 1L));
        writer.publish(type, fields("value", 2L));
        hub.safePoint(state(2L, 1L));

        assertEquals(2, batches.size());
        assertTrue(batches.getFirst().initial());
        assertEquals(ClientStateBatch.ResyncReason.QUEUE_OVERFLOW, batches.getLast().resyncReason());
        assertTrue(batches.getLast().events().isEmpty());
    }

    @Test
    @DisplayName("A broken listener is isolated without stopping another subscriber")
    void listenerFailureCancelsOnlyThatSubscriber() {
        AtomicInteger failures = new AtomicInteger();
        ClientEventHub hub = new ClientEventHub(8, ignored -> failures.incrementAndGet());
        List<ClientEventBatch> healthy = new ArrayList<>();
        var broken = hub.serviceForOwner(1L, "example:broken", Optional::empty)
                .subscribe(batch -> { if (!batch.initial()) throw new IllegalStateException("expected"); });
        hub.serviceForOwner(2L, "example:healthy", Optional::empty).subscribe(healthy::add);
        ClientStateSnapshot first = state(1L, 1L);
        hub.safePoint(first);
        assertTrue(record(hub, first, 1L));
        hub.safePoint(state(2L, 1L));

        assertTrue(broken.isCancelled());
        assertEquals(1, failures.get());
        assertEquals(2, healthy.size());
        assertEquals(1, healthy.getLast().events().size());
        assertEquals(1, hub.metrics().activeSubscriptions());
    }

    private static boolean record(ClientEventHub hub, ClientStateSnapshot state, long generation) {
        return hub.recordBehavior(generation, BehaviorEvent.Kind.BLOCK_BREAK,
                BehaviorEvent.Phase.REQUESTED, BehaviorEvent.Source.LOCAL_INTENT,
                () -> fields("blockX", 1L), Optional.of(state));
    }

    private static EventValue.ObjectValue fields(String key, long value) {
        return new EventValue.ObjectValue(Map.of(key, new EventValue.IntegerNumber(value)));
    }

    private static ClientStateSnapshot state(long sequence, long generation) {
        var time = new ClientStateSnapshot.LogicalTime(sequence, sequence * 1_000_000L, false);
        var world = new ClientStateSnapshot.WorldSession(generation, generation > 0L, "example:world");
        return new ClientStateSnapshot(sequence, time, world,
                ClientStateSnapshot.Environment.UNAVAILABLE, ClientStateSnapshot.Player.UNAVAILABLE);
    }
}
