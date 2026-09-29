package dev.luxloader.core.runtime;

import dev.luxloader.api.event.BehaviorEvent;
import dev.luxloader.api.event.ClientEvent;
import dev.luxloader.api.event.ClientEventBatch;
import dev.luxloader.api.event.ClientEventService;
import dev.luxloader.api.event.ClientEventSubscription;
import dev.luxloader.api.event.CustomEvent;
import dev.luxloader.api.event.CustomEventRegistration;
import dev.luxloader.api.event.CustomEventType;
import dev.luxloader.api.event.EventTypeId;
import dev.luxloader.api.event.EventValue;
import dev.luxloader.api.state.ClientStateBatch;
import dev.luxloader.api.state.ClientStateSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded, client-safe-point fanout for built-in and plugin-defined events. */
public final class ClientEventHub {
    public static final int DEFAULT_QUEUE_CAPACITY = 256;

    private final Object lock = new Object();
    private final int queueCapacity;
    private final Consumer<Throwable> listenerFailure;
    private final Map<Long, Subscription> subscriptions = new LinkedHashMap<>();
    private final Map<CustomEventType, TypeRegistration> customTypes = new LinkedHashMap<>();
    private final ArrayDeque<QueuedEvent> pending = new ArrayDeque<>();

    private long nextSubscriptionId;
    private long nextRegistrationId;
    private long eventSequence;
    private long highestSessionGeneration = -1L;
    private long droppedEvents;
    private long queuePeak;
    private long queueOverflowCount;
    private long staleEventsRejected;
    private long deliveredBatchCount;
    private long deliveredEventCount;
    private long lastStateCaptureFailureEpoch;
    private ClientStateSnapshot latestSnapshot;
    private ClientStateBatch.ResyncReason pendingResync = ClientStateBatch.ResyncReason.NONE;
    private boolean blockedUntilSafePoint;

    public ClientEventHub() {
        this(DEFAULT_QUEUE_CAPACITY, ignored -> { });
    }

    ClientEventHub(int queueCapacity, Consumer<Throwable> listenerFailure) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        this.queueCapacity = queueCapacity;
        this.listenerFailure = Objects.requireNonNull(listenerFailure, "listenerFailure");
    }

    /** Plugin-instance scoped service. */
    public ClientEventService serviceForOwner(long ownerToken, String ownerPluginId,
                                              Supplier<Optional<ClientStateSnapshot>> latestState) {
        return new OwnerService(ownerToken, EventTypeId.parse(ownerPluginId), latestState);
    }

    /** Cancel every event subscription and custom type created by a replaced or failed plugin instance. */
    public void releaseOwner(long ownerToken) {
        synchronized (lock) {
            List<Subscription> ownedSubscriptions = subscriptions.values().stream()
                    .filter(subscription -> subscription.ownerToken == ownerToken)
                    .toList();
            ownedSubscriptions.forEach(this::cancelLocked);

            List<TypeRegistration> ownedTypes = customTypes.values().stream()
                    .filter(registration -> registration.ownerToken == ownerToken)
                    .toList();
            for (TypeRegistration registration : ownedTypes) {
                registration.cancelled.set(true);
                customTypes.remove(registration.type, registration);
            }
            pending.removeIf(event -> event.publisherOwnerToken == ownerToken);
            clearIfUnusedLocked();
        }
    }

    public boolean hasSubscribers() {
        synchronized (lock) { return !subscriptions.isEmpty(); }
    }

    /** Record only when a live subscriber has a matching state/session context. */
    public boolean recordBehavior(long observedSessionGeneration,
            BehaviorEvent.Kind kind, BehaviorEvent.Phase phase, BehaviorEvent.Source source,
            Supplier<EventValue.ObjectValue> fieldsFactory,
            Optional<ClientStateSnapshot> latestState) {
        return recordBehavior(observedSessionGeneration, kind, phase, source,
                Optional.empty(), fieldsFactory, latestState);
    }

    /** Record a copied behavior fact with the local-player identity captured at its source, if any. */
    public boolean recordBehavior(long observedSessionGeneration,
            BehaviorEvent.Kind kind, BehaviorEvent.Phase phase, BehaviorEvent.Source source,
            Optional<ClientStateSnapshot.EntityIdentity> playerIdentity,
            Supplier<EventValue.ObjectValue> fieldsFactory,
            Optional<ClientStateSnapshot> latestState) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(playerIdentity, "playerIdentity");
        Objects.requireNonNull(fieldsFactory, "fieldsFactory");
        Objects.requireNonNull(latestState, "latestState");
        ClientStateSnapshot snapshot = latestState.orElse(null);
        synchronized (lock) {
            if (subscriptions.isEmpty() || blockedUntilSafePoint) return false;
            if (observedSessionGeneration < 0L) {
                staleEventsRejected++;
                return false;
            }
            if (playerIdentity.isPresent()
                    && playerIdentity.get().sessionGeneration() != observedSessionGeneration) {
                staleEventsRejected++;
                return false;
            }
            if (snapshot != null && snapshot.world().generation() != observedSessionGeneration) {
                staleEventsRejected++;
                return false;
            }
            if (highestSessionGeneration >= 0 && observedSessionGeneration < highestSessionGeneration) {
                staleEventsRejected++;
                return false;
            }
            if (highestSessionGeneration < 0) highestSessionGeneration = observedSessionGeneration;
            if (observedSessionGeneration > highestSessionGeneration) {
                beginGapLocked(ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED, pending.size());
                highestSessionGeneration = observedSessionGeneration;
                latestSnapshot = null;
                blockedUntilSafePoint = true;
                return false;
            }
        }

        EventValue.ObjectValue fields = Objects.requireNonNull(fieldsFactory.get(), "fieldsFactory result");
        synchronized (lock) {
            if (subscriptions.isEmpty() || blockedUntilSafePoint
                    || observedSessionGeneration != highestSessionGeneration) return false;
            if (pending.size() >= queueCapacity) {
                queueOverflowCount++;
                beginGapLocked(ClientStateBatch.ResyncReason.QUEUE_OVERFLOW, pending.size() + 1L);
                blockedUntilSafePoint = true;
                return false;
            }
            long sequence = nextSequenceLocked();
            QueuedEvent event = new QueuedEvent(sequence, observedSessionGeneration,
                    snapshot == null ? null : snapshot.time(),
                    new BehaviorBody(kind, phase, source, playerIdentity, fields), -1L);
            pending.addLast(event);
            queuePeak = Math.max(queuePeak, pending.size());
            return true;
        }
    }

    /** Drain queued events on the caller's client safe point. Listener code runs synchronously here. */
    public void safePoint(ClientStateSnapshot snapshot) {
        safePoint(snapshot, 0L);
    }

    /** Drain while also consuming state-capture failure boundaries observed since the last safe point. */
    public void safePoint(ClientStateSnapshot snapshot, long stateCaptureFailureEpoch) {
        if (stateCaptureFailureEpoch < 0L) {
            throw new IllegalArgumentException("stateCaptureFailureEpoch must not be negative");
        }
        List<Delivery> deliveries = new ArrayList<>();
        synchronized (lock) {
            if (stateCaptureFailureEpoch > lastStateCaptureFailureEpoch) {
                lastStateCaptureFailureEpoch = stateCaptureFailureEpoch;
                if (!subscriptions.isEmpty()) {
                    beginGapLocked(ClientStateBatch.ResyncReason.SAMPLE_CAPTURE_FAILED, pending.size());
                    blockedUntilSafePoint = true;
                    latestSnapshot = null;
                }
            }
            if (subscriptions.isEmpty()) {
                pending.clear();
                pendingResync = ClientStateBatch.ResyncReason.NONE;
                droppedEvents = 0L;
                blockedUntilSafePoint = false;
                latestSnapshot = null;
                return;
            }
            if (snapshot == null) {
                if (pendingResync == ClientStateBatch.ResyncReason.NONE) {
                    beginGapLocked(ClientStateBatch.ResyncReason.SAMPLE_CAPTURE_FAILED, pending.size());
                }
                blockedUntilSafePoint = true;
                latestSnapshot = null;
                return;
            }

            long sessionGeneration = snapshot.world().generation();
            if (highestSessionGeneration >= 0 && sessionGeneration < highestSessionGeneration) {
                staleEventsRejected++;
                return;
            }
            if (highestSessionGeneration < 0) {
                highestSessionGeneration = sessionGeneration;
            } else if (sessionGeneration > highestSessionGeneration) {
                beginGapLocked(ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED, pending.size());
                highestSessionGeneration = sessionGeneration;
                blockedUntilSafePoint = true;
            }
            latestSnapshot = snapshot;

            ClientStateBatch.ResyncReason resync = pendingResync;
            long dropped = droppedEvents;
            List<QueuedEvent> currentEvents = new ArrayList<>();
            if (resync == ClientStateBatch.ResyncReason.NONE && !blockedUntilSafePoint) {
                while (!pending.isEmpty()) {
                    QueuedEvent event = pending.removeFirst();
                    if (event.sessionGeneration < 0 || event.sessionGeneration == sessionGeneration) {
                        currentEvents.add(event.withSessionAndTime(sessionGeneration,
                                event.logicalTime == null ? snapshot.time() : event.logicalTime));
                    } else {
                        droppedEvents = saturatingAdd(droppedEvents, 1L);
                        staleEventsRejected++;
                        beginGapLocked(ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED, 0L);
                        resync = pendingResync;
                        dropped = droppedEvents;
                        currentEvents.clear();
                        break;
                    }
                }
            }

            for (Subscription subscription : List.copyOf(subscriptions.values())) {
                if (subscription.cancelled.get()) continue;
                if (subscription.initialPending) {
                    deliveries.add(new Delivery(subscription,
                            batch(subscription.lastEventSequence, subscription.lastEventSequence,
                                    snapshot, List.of(), true, ClientStateBatch.ResyncReason.NONE, 0L)));
                    subscription.initialPending = false;
                    if (resync != ClientStateBatch.ResyncReason.NONE) {
                        subscription.lastEventSequence = eventSequence;
                        continue;
                    }
                }
                if (resync != ClientStateBatch.ResyncReason.NONE) {
                    deliveries.add(new Delivery(subscription,
                            batch(subscription.lastEventSequence + 1L, Math.max(subscription.lastEventSequence, eventSequence),
                                    snapshot, List.of(), false, resync, dropped)));
                    subscription.lastEventSequence = eventSequence;
                    continue;
                }
                List<ClientEvent> events = currentEvents.stream()
                        .filter(event -> event.sequence > subscription.lastEventSequence)
                        .map(event -> event.materialize())
                        .toList();
                if (!events.isEmpty()) {
                    long first = events.get(0).stamp().sequence();
                    long last = events.get(events.size() - 1).stamp().sequence();
                    deliveries.add(new Delivery(subscription,
                            batch(first, last, snapshot, events, false, ClientStateBatch.ResyncReason.NONE, 0L)));
                    subscription.lastEventSequence = last;
                }
            }
            pending.clear();
            pendingResync = ClientStateBatch.ResyncReason.NONE;
            droppedEvents = 0L;
            blockedUntilSafePoint = false;
            deliveredBatchCount = saturatingAdd(deliveredBatchCount, deliveries.size());
            for (Delivery delivery : deliveries) {
                deliveredEventCount = saturatingAdd(deliveredEventCount, delivery.batch.events().size());
            }
        }

        for (Delivery delivery : deliveries) {
            Subscription subscription = delivery.subscription;
            if (subscription.cancelled.get()) continue;
            try {
                subscription.batchListener.accept(delivery.batch);
            } catch (RuntimeException | LinkageError error) {
                cancel(subscription);
                reportFailure(error);
            }
        }
    }

    Metrics metrics() {
        synchronized (lock) {
            return new Metrics(subscriptions.size(), customTypes.size(), pending.size(), queuePeak,
                    queueOverflowCount, droppedEvents, staleEventsRejected, deliveredBatchCount,
                    deliveredEventCount, blockedUntilSafePoint);
        }
    }

    private void reportFailure(Throwable error) {
        try {
            listenerFailure.accept(error);
        } catch (RuntimeException ignored) {
            // Error reporting must not escape the game update loop.
        }
    }

    private static ClientEventBatch batch(long first, long last, ClientStateSnapshot snapshot,
                                          List<ClientEvent> events, boolean initial,
                                          ClientStateBatch.ResyncReason reason, long dropped) {
        long safeFirst = Math.max(0L, first);
        long safeLast = Math.max(safeFirst, last);
        return new ClientEventBatch(safeFirst, safeLast, snapshot, events, initial, reason, dropped);
    }

    private void beginGapLocked(ClientStateBatch.ResyncReason reason, long dropped) {
        if (reason == ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED
                || pendingResync == ClientStateBatch.ResyncReason.NONE) {
            pendingResync = reason;
        }
        droppedEvents = saturatingAdd(droppedEvents, dropped);
        pending.clear();
    }

    private long nextSequenceLocked() {
        if (eventSequence < Long.MAX_VALUE) eventSequence++;
        return eventSequence;
    }

    private void cancel(Subscription subscription) {
        synchronized (lock) {
            cancelLocked(subscription);
            clearIfUnusedLocked();
        }
    }

    private void cancelLocked(Subscription subscription) {
        if (subscription.cancelled.compareAndSet(false, true)) subscriptions.remove(subscription.id);
    }

    private void clearIfUnusedLocked() {
        if (!subscriptions.isEmpty()) return;
        pending.clear();
        pendingResync = ClientStateBatch.ResyncReason.NONE;
        droppedEvents = 0L;
        blockedUntilSafePoint = false;
        latestSnapshot = null;
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    record Metrics(int activeSubscriptions, int customTypes, int pendingEvents, long queuePeak,
                   long queueOverflowCount, long droppedEvents, long staleEventsRejected,
                   long deliveredBatchCount, long deliveredEventCount, boolean blockedUntilSafePoint) { }

    private final class OwnerService implements ClientEventService {
        private final long ownerToken;
        private final EventTypeId ownerId;
        private final Supplier<Optional<ClientStateSnapshot>> latestState;

        private OwnerService(long ownerToken, EventTypeId ownerId,
                             Supplier<Optional<ClientStateSnapshot>> latestState) {
            this.ownerToken = ownerToken;
            this.ownerId = ownerId;
            this.latestState = Objects.requireNonNull(latestState, "latestState");
        }

        @Override public ClientEventSubscription subscribe(Consumer<ClientEventBatch> listener) {
            Objects.requireNonNull(listener, "listener");
            return addSubscription(listener, null);
        }

        @Override public ClientEventSubscription subscribeCustom(CustomEventType type,
                                                                  Consumer<ClientEventBatch> listener) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(listener, "listener");
            synchronized (lock) {
                if (!customTypes.containsKey(type)) {
                    throw new IllegalArgumentException("Custom event type is not registered: " + type);
                }
            }
            return addSubscription(batch -> {
                List<ClientEvent> matching = batch.events().stream()
                        .filter(event -> event instanceof CustomEvent custom && custom.type().equals(type))
                        .toList();
                if (!matching.isEmpty() || batch.initial()
                        || batch.resyncReason() != ClientStateBatch.ResyncReason.NONE) {
                    listener.accept(new ClientEventBatch(batch.firstSequence(), batch.lastSequence(),
                            batch.snapshot(), matching, batch.initial(), batch.resyncReason(),
                            batch.droppedEvents()));
                }
            }, type);
        }

        private ClientEventSubscription addSubscription(Consumer<ClientEventBatch> listener,
                                                        CustomEventType customFilter) {
            synchronized (lock) {
                long id = ++nextSubscriptionId;
                Subscription subscription = new Subscription(id, ownerToken, listener, customFilter,
                        eventSequence);
                subscriptions.put(id, subscription);
                return subscription;
            }
        }

        @Override public CustomEventRegistration registerCustomType(EventTypeId id, int version) {
            Objects.requireNonNull(id, "id");
            if (!id.isOwnedBy(ownerId)) {
                throw new IllegalArgumentException("Plugin " + ownerId + " cannot register custom event type " + id);
            }
            CustomEventType type = new CustomEventType(id, version);
            synchronized (lock) {
                if (customTypes.containsKey(type)) {
                    throw new IllegalArgumentException("Custom event type is already registered: " + type);
                }
                TypeRegistration registration = new TypeRegistration(++nextRegistrationId, ownerToken, type);
                customTypes.put(type, registration);
                return registration;
            }
        }

        @Override public void publish(CustomEventType type, EventValue.ObjectValue payload) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(payload, "payload");
            Optional<ClientStateSnapshot> state = latestState.get();
            synchronized (lock) {
                TypeRegistration registration = customTypes.get(type);
                if (registration == null || registration.ownerToken != ownerToken) {
                    throw new IllegalArgumentException("Custom event type is not owned by this plugin instance: " + type);
                }
                if (subscriptions.isEmpty() || blockedUntilSafePoint) return;
                if (pending.size() >= queueCapacity) {
                    queueOverflowCount++;
                    beginGapLocked(ClientStateBatch.ResyncReason.QUEUE_OVERFLOW, pending.size() + 1L);
                    blockedUntilSafePoint = true;
                    return;
                }
                ClientStateSnapshot snapshot = state.orElse(null);
                long eventSession = snapshot == null ? -1L : snapshot.world().generation();
                if (eventSession >= 0 && highestSessionGeneration >= 0
                        && eventSession < highestSessionGeneration) {
                    staleEventsRejected++;
                    return;
                }
                long sequence = nextSequenceLocked();
                pending.addLast(new QueuedEvent(sequence, eventSession,
                        snapshot == null ? null : snapshot.time(), new CustomBody(type, payload), ownerToken));
                queuePeak = Math.max(queuePeak, pending.size());
            }
        }
    }

    private final class TypeRegistration implements CustomEventRegistration {
        private final long registrationId;
        private final long ownerToken;
        private final CustomEventType type;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private TypeRegistration(long registrationId, long ownerToken, CustomEventType type) {
            this.registrationId = registrationId;
            this.ownerToken = ownerToken;
            this.type = type;
        }

        @Override public CustomEventType type() { return type; }

        @Override public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            synchronized (lock) {
                customTypes.remove(type, this);
            }
            return true;
        }

        @Override public boolean isCancelled() { return cancelled.get(); }

        @Override public String toString() { return "CustomEventRegistration[" + registrationId + ", " + type + "]"; }
    }

    private final class Subscription implements ClientEventSubscription {
        private final long id;
        private final long ownerToken;
        private final Consumer<ClientEventBatch> batchListener;
        @SuppressWarnings("unused")
        private final CustomEventType customFilter;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private boolean initialPending = true;
        private long lastEventSequence;

        private Subscription(long id, long ownerToken, Consumer<ClientEventBatch> batchListener,
                             CustomEventType customFilter, long initialSequence) {
            this.id = id;
            this.ownerToken = ownerToken;
            this.batchListener = batchListener;
            this.customFilter = customFilter;
            this.lastEventSequence = initialSequence;
            this.initialPending = true;
        }

        @Override public boolean cancel() {
            boolean wasActive = !cancelled.get();
            ClientEventHub.this.cancel(this);
            return wasActive;
        }

        @Override public boolean isCancelled() { return cancelled.get(); }
    }

    private record Delivery(Subscription subscription, ClientEventBatch batch) { }

    private sealed interface EventBody permits BehaviorBody, CustomBody {
        ClientEvent materialize(QueuedEvent event);
    }

    private record BehaviorBody(BehaviorEvent.Kind kind, BehaviorEvent.Phase phase,
                                BehaviorEvent.Source source,
                                Optional<ClientStateSnapshot.EntityIdentity> playerIdentity,
                                EventValue.ObjectValue fields) implements EventBody {
        @Override public ClientEvent materialize(QueuedEvent event) {
            return new BehaviorEvent(new ClientEvent.EventStamp(event.sequence, event.sessionGeneration,
                    event.logicalTime), kind, phase, source, playerIdentity, fields);
        }
    }

    private record CustomBody(CustomEventType type, EventValue.ObjectValue payload) implements EventBody {
        @Override public ClientEvent materialize(QueuedEvent event) {
            return new CustomEvent(new ClientEvent.EventStamp(event.sequence, event.sessionGeneration,
                    event.logicalTime), type, payload);
        }
    }

    private record QueuedEvent(long sequence, long sessionGeneration,
                               ClientStateSnapshot.LogicalTime logicalTime,
                               EventBody body, long publisherOwnerToken) {
        private QueuedEvent withSessionAndTime(long generation, ClientStateSnapshot.LogicalTime time) {
            return new QueuedEvent(sequence, generation, time, body, publisherOwnerToken);
        }

        private ClientEvent materialize() { return body.materialize(this); }
    }
}
