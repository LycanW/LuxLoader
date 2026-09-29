package dev.luxloader.core.runtime;

import dev.luxloader.api.state.ClientStateBatch;
import dev.luxloader.api.state.ClientStateService;
import dev.luxloader.api.state.ClientStateSnapshot;
import dev.luxloader.api.state.ClientStateSubscription;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Core-owned, game-independent state publisher. Capture may happen at a client tick boundary;
 * listeners are only invoked by {@link #safePoint(boolean, long, BiFunction)}.
 */
public final class ClientStateHub {
    public static final int DEFAULT_QUEUE_CAPACITY = 64;
    private static final long INITIAL_CLOCK = Long.MIN_VALUE;

    private final Object lock = new Object();
    private final int queueCapacity;
    private final LongSupplier monotonicClock;
    private final Consumer<Throwable> listenerFailure;
    private final Map<Long, Subscription> subscriptions = new LinkedHashMap<>();
    private final ArrayDeque<ClientStateSnapshot> pending = new ArrayDeque<>();

    private long nextSubscriptionId;
    private long sampleSequence;
    private long logicalTick;
    private long logicalNanos;
    private long lastMonotonicNanos = INITIAL_CLOCK;
    private boolean lastPaused;
    private boolean pauseKnown;
    private long highestSessionGeneration = -1L;
    private ClientStateSnapshot latest;
    private ClientStateBatch.ResyncReason pendingResync = ClientStateBatch.ResyncReason.NONE;
    private long droppedSamples;

    private long queuePeak;
    private long queueOverflowCount;
    private long staleSamplesRejected;
    private long deliveredBatchCount;
    private long deliveredSprintTransitionCount;

    public ClientStateHub() {
        this(System::nanoTime, DEFAULT_QUEUE_CAPACITY, ignored -> { });
    }

    ClientStateHub(LongSupplier monotonicClock, int queueCapacity,
                   Consumer<Throwable> listenerFailure) {
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        this.queueCapacity = queueCapacity;
        this.listenerFailure = Objects.requireNonNull(listenerFailure, "listenerFailure");
    }

    /** Plugin-instance scoped service. The opaque token changes on every HostServices instance. */
    public ClientStateService serviceForOwner(long ownerToken) {
        return new OwnerService(ownerToken);
    }

    /** Cancel every subscription created by a replaced or failed plugin instance. */
    public void releaseOwner(long ownerToken) {
        synchronized (lock) {
            List<Subscription> owned = subscriptions.values().stream()
                    .filter(subscription -> subscription.ownerToken == ownerToken)
                    .toList();
            owned.forEach(this::cancelLocked);
            clearIfUnusedLocked();
        }
    }

    public boolean hasSubscribers() {
        synchronized (lock) {
            return !subscriptions.isEmpty();
        }
    }

    /** True when the next safe point needs a fresh baseline but no sample is available yet. */
    public boolean needsInitialSnapshot() {
        synchronized (lock) {
            return latest == null && !subscriptions.isEmpty();
        }
    }

    /**
     * Record one Minecraft client tick. The factory is not called when there are no subscribers, so
     * no event payload, player inventory description, or biome lookup is created on the idle path.
     */
    public void clientTick(boolean paused,
                           BiFunction<Long, ClientStateSnapshot.LogicalTime, ClientStateSnapshot> snapshotFactory) {
        Objects.requireNonNull(snapshotFactory, "snapshotFactory");
        CaptureContext context;
        boolean capture;
        synchronized (lock) {
            updateClockLocked(paused);
            if (!paused) logicalTick = saturatingAdd(logicalTick, 1L);
            sampleSequence = saturatingAdd(sampleSequence, 1L);
            context = contextLocked(paused);
            capture = !subscriptions.isEmpty();
        }
        if (capture) capture(context, snapshotFactory);
    }

    /**
     * Observe session and pause transitions on every client loop, including loops with no game tick.
     * Plugin callbacks are drained only after any small boundary capture has completed.
     */
    public void safePoint(boolean paused, long observedSessionGeneration,
                          BiFunction<Long, ClientStateSnapshot.LogicalTime, ClientStateSnapshot> snapshotFactory) {
        Objects.requireNonNull(snapshotFactory, "snapshotFactory");
        CaptureContext context = null;
        boolean capture = false;
        synchronized (lock) {
            boolean pauseChanged = !pauseKnown || lastPaused != paused;
            updateClockLocked(paused);

            boolean sessionChanged = observedSessionGeneration >= 0
                    && observedSessionGeneration > highestSessionGeneration;
            if (observedSessionGeneration >= 0 && observedSessionGeneration < highestSessionGeneration) {
                staleSamplesRejected++;
            } else if (observedSessionGeneration >= 0) {
                highestSessionGeneration = observedSessionGeneration;
            }

            if (sessionChanged) {
                markGapLocked(ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED);
                if (subscriptions.isEmpty()) {
                    latest = null;
                    pending.clear();
                    pendingResync = ClientStateBatch.ResyncReason.NONE;
                    droppedSamples = 0L;
                } else if (latest == null || latest.world().generation() != observedSessionGeneration) {
                    if (!pending.isEmpty()) {
                        droppedSamples = saturatingAdd(droppedSamples, pending.size());
                        pending.clear();
                    }
                    latest = null;
                    capture = true;
                }
            }

            if (!subscriptions.isEmpty() && observedSessionGeneration >= 0
                    && observedSessionGeneration == highestSessionGeneration
                    && (latest == null || latest.world().generation() != observedSessionGeneration)) {
                capture = true;
            }

            pauseKnown = true;
            if (!subscriptions.isEmpty()) {
                if (latest == null) {
                    capture = true;
                } else if (pauseChanged && !capture) {
                    sampleSequence = saturatingAdd(sampleSequence, 1L);
                    ClientStateSnapshot.LogicalTime time = logicalTimeLocked(paused);
                    enqueueLocked(withTimeAndSequence(latest, sampleSequence, time),
                            ClientStateBatch.ResyncReason.NONE);
                }
            }

            if (capture) {
                sampleSequence = saturatingAdd(sampleSequence, 1L);
                context = contextLocked(paused);
            }
        }

        if (context != null) capture(context, snapshotFactory);
        drainPending();
    }

    /** Current immutable sample while at least one plugin is subscribed. */
    public Optional<ClientStateSnapshot> current() {
        synchronized (lock) {
            return Optional.ofNullable(latest);
        }
    }

    Metrics metrics() {
        synchronized (lock) {
            return new Metrics(subscriptions.size(), pending.size(), queuePeak, queueOverflowCount,
                    droppedSamples, staleSamplesRejected, deliveredBatchCount,
                    deliveredSprintTransitionCount);
        }
    }

    private void capture(CaptureContext context,
                         BiFunction<Long, ClientStateSnapshot.LogicalTime, ClientStateSnapshot> factory) {
        synchronized (lock) {
            if (subscriptions.isEmpty()) return;
        }
        try {
            ClientStateSnapshot snapshot = factory.apply(context.sequence(), context.time());
            if (snapshot == null) {
                throw new IllegalStateException("Snapshot factory returned null");
            }
            if (snapshot.sampleSequence() != context.sequence()) {
                throw new IllegalArgumentException("Snapshot sequence does not match its capture boundary");
            }
            if (!snapshot.time().equals(context.time())) {
                throw new IllegalArgumentException("Snapshot time does not match its capture boundary");
            }
            synchronized (lock) {
                enqueueLocked(snapshot, ClientStateBatch.ResyncReason.NONE);
            }
        } catch (RuntimeException | LinkageError e) {
            synchronized (lock) {
                if (!subscriptions.isEmpty()) {
                    droppedSamples = saturatingAdd(droppedSamples, pending.size() + 1L);
                    pending.clear();
                    pendingResync = ClientStateBatch.ResyncReason.SAMPLE_CAPTURE_FAILED;
                    latest = null;
                }
            }
            try {
                listenerFailure.accept(e);
            } catch (RuntimeException ignored) {
                // Error reporting must not escape the game update loop.
            }
        }
    }

    private void enqueueLocked(ClientStateSnapshot snapshot, ClientStateBatch.ResyncReason reason) {
        if (subscriptions.isEmpty()) return;
        long generation = snapshot.world().generation();
        if (generation < highestSessionGeneration) {
            staleSamplesRejected++;
            return;
        }
        if (generation > highestSessionGeneration) {
            highestSessionGeneration = generation;
            reason = ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED;
        }
        if (latest != null && generation == latest.world().generation()
                && snapshot.sampleSequence() <= latest.sampleSequence()) {
            staleSamplesRejected++;
            return;
        }

        latest = snapshot;
        if (reason != ClientStateBatch.ResyncReason.NONE) {
            markGapLocked(reason);
        }
        if (pendingResync != ClientStateBatch.ResyncReason.NONE) {
            if (!pending.isEmpty()) {
                droppedSamples = saturatingAdd(droppedSamples, pending.size());
                pending.clear();
            }
            pending.addLast(snapshot);
        } else if (pending.size() >= queueCapacity) {
            droppedSamples = saturatingAdd(droppedSamples, pending.size());
            pending.clear();
            queueOverflowCount++;
            pendingResync = ClientStateBatch.ResyncReason.QUEUE_OVERFLOW;
            pending.addLast(snapshot);
        } else {
            pending.addLast(snapshot);
        }
        queuePeak = Math.max(queuePeak, pending.size());
    }

    private void markGapLocked(ClientStateBatch.ResyncReason reason) {
        if (reason == ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED
                || pendingResync == ClientStateBatch.ResyncReason.NONE) {
            pendingResync = reason;
        }
    }

    private void drainPending() {
        List<Delivery> deliveries = new ArrayList<>();
        synchronized (lock) {
            if (subscriptions.isEmpty()) {
                pending.clear();
                pendingResync = ClientStateBatch.ResyncReason.NONE;
                droppedSamples = 0L;
                latest = null;
                return;
            }

            List<ClientStateSnapshot> samples = List.copyOf(pending);
            pending.clear();
            ClientStateBatch.ResyncReason resync = pendingResync;
            long dropped = droppedSamples;
            long currentSessionGeneration = highestSessionGeneration;
            ClientStateSnapshot finalSnapshot = latest;
            boolean finalSnapshotIsCurrent = finalSnapshot != null
                    && (currentSessionGeneration < 0
                    || finalSnapshot.world().generation() == currentSessionGeneration);
            if (finalSnapshotIsCurrent || resync == ClientStateBatch.ResyncReason.NONE) {
                pendingResync = ClientStateBatch.ResyncReason.NONE;
                droppedSamples = 0L;
            }

            for (Subscription subscription : List.copyOf(subscriptions.values())) {
                if (subscription.cancelled.get()) continue;
                boolean deliveredInitial = false;
                ClientStateSnapshot baseline = subscription.initialSnapshot;
                if (subscription.initialPending) {
                    if (!isContinuousInitialBaseline(baseline, samples, finalSnapshot,
                            currentSessionGeneration, resync)) {
                        subscription.initialSnapshot = null;
                        baseline = finalSnapshotIsCurrent ? finalSnapshot : null;
                    }
                    if (baseline != null) {
                        deliveries.add(new Delivery(subscription,
                                batch(baseline.sampleSequence(), baseline.sampleSequence(), baseline,
                                        List.of(), true, ClientStateBatch.ResyncReason.NONE, 0L)));
                        setCursor(subscription, baseline);
                        subscription.initialPending = false;
                        subscription.initialSnapshot = null;
                        deliveredInitial = true;
                    }
                }

                if (!finalSnapshotIsCurrent || subscription.cancelled.get()) continue;
                boolean sessionMismatch = subscription.sessionGeneration >= 0
                        && subscription.sessionGeneration != finalSnapshot.world().generation();
                if (!deliveredInitial && (resync != ClientStateBatch.ResyncReason.NONE || sessionMismatch)) {
                    ClientStateBatch.ResyncReason reason = resync == ClientStateBatch.ResyncReason.NONE
                            ? ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED : resync;
                    if (finalSnapshot.sampleSequence() > subscription.lastSequence || sessionMismatch) {
                        deliveries.add(new Delivery(subscription,
                                batch(subscription.lastSequence + 1L, finalSnapshot.sampleSequence(),
                                        finalSnapshot, List.of(), false, reason, dropped)));
                        setCursor(subscription, finalSnapshot);
                    }
                    continue;
                }

                List<ClientStateSnapshot> afterCursor = samples.stream()
                        .filter(sample -> sample.sampleSequence() > subscription.lastSequence)
                        .toList();
                if (afterCursor.isEmpty()) continue;
                if (afterCursor.stream().anyMatch(sample ->
                        sample.world().generation() != subscription.sessionGeneration)) {
                    deliveries.add(new Delivery(subscription,
                            batch(subscription.lastSequence + 1L, finalSnapshot.sampleSequence(), finalSnapshot,
                                    List.of(), false, ClientStateBatch.ResyncReason.WORLD_SESSION_CHANGED, 0L)));
                    setCursor(subscription, finalSnapshot);
                    continue;
                }

                List<ClientStateBatch.SprintTransition> transitions = new ArrayList<>();
                for (ClientStateSnapshot sample : afterCursor) {
                    ClientStateSnapshot.Player player = sample.player();
                    ClientStateSnapshot.EntityIdentity identity = player.valid() ? player.identity() : null;
                    if (identity != null && identity.equals(subscription.playerIdentity)
                            && subscription.sprinting != player.sprinting()) {
                        transitions.add(new ClientStateBatch.SprintTransition(sample.sampleSequence(), identity,
                                subscription.sprinting, player.sprinting(),
                                ClientStateBatch.SprintTransition.Source.CLIENT_OBSERVED_STATE_DIFFERENCE));
                    }
                    subscription.playerIdentity = identity;
                    subscription.sprinting = player.valid() && player.sprinting();
                    subscription.sessionGeneration = sample.world().generation();
                }
                ClientStateSnapshot last = afterCursor.get(afterCursor.size() - 1);
                deliveries.add(new Delivery(subscription,
                        batch(afterCursor.get(0).sampleSequence(), last.sampleSequence(), last, transitions,
                                false, ClientStateBatch.ResyncReason.NONE, 0L)));
                setCursor(subscription, last);
            }
            deliveredBatchCount = saturatingAdd(deliveredBatchCount, deliveries.size());
            for (Delivery delivery : deliveries) {
                deliveredSprintTransitionCount = saturatingAdd(deliveredSprintTransitionCount,
                        delivery.batch().sprintTransitions().size());
            }
        }

        for (Delivery delivery : deliveries) {
            Subscription subscription = delivery.subscription();
            if (subscription.cancelled.get()) continue;
            try {
                subscription.listener.accept(delivery.batch());
            } catch (RuntimeException | LinkageError e) {
                cancel(subscription);
                try {
                    listenerFailure.accept(e);
                } catch (RuntimeException ignored) {
                    // Error reporting must not escape the game update loop.
                }
            }
        }
    }

    private static ClientStateBatch batch(long first, long last, ClientStateSnapshot snapshot,
                                          List<ClientStateBatch.SprintTransition> transitions,
                                          boolean initial, ClientStateBatch.ResyncReason reason, long dropped) {
        long safeFirst = Math.min(first, snapshot.sampleSequence());
        long safeLast = Math.max(safeFirst, Math.min(last, snapshot.sampleSequence()));
        return new ClientStateBatch(safeFirst, safeLast, snapshot, transitions, initial, reason, dropped);
    }

    private static boolean isContinuousInitialBaseline(ClientStateSnapshot baseline,
            List<ClientStateSnapshot> samples, ClientStateSnapshot latest, long currentSessionGeneration,
            ClientStateBatch.ResyncReason resync) {
        if (baseline == null || latest == null || resync != ClientStateBatch.ResyncReason.NONE) return false;
        long sessionGeneration = latest.world().generation();
        if (currentSessionGeneration >= 0 && sessionGeneration != currentSessionGeneration) return false;
        if (baseline.world().generation() != sessionGeneration
                || baseline.sampleSequence() > latest.sampleSequence()) return false;

        long expectedSequence = baseline.sampleSequence();
        for (ClientStateSnapshot sample : samples) {
            if (sample.sampleSequence() <= baseline.sampleSequence()) continue;
            if (expectedSequence == Long.MAX_VALUE || sample.sampleSequence() != expectedSequence + 1L
                    || sample.world().generation() != sessionGeneration) return false;
            expectedSequence = sample.sampleSequence();
        }
        return expectedSequence == latest.sampleSequence();
    }

    private static void setCursor(Subscription subscription, ClientStateSnapshot snapshot) {
        subscription.lastSequence = snapshot.sampleSequence();
        subscription.sessionGeneration = snapshot.world().generation();
        subscription.playerIdentity = snapshot.player().valid() ? snapshot.player().identity() : null;
        subscription.sprinting = snapshot.player().valid() && snapshot.player().sprinting();
    }

    private static ClientStateSnapshot withTimeAndSequence(ClientStateSnapshot snapshot, long sequence,
                                                            ClientStateSnapshot.LogicalTime time) {
        return new ClientStateSnapshot(sequence, time, snapshot.world(), snapshot.environment(), snapshot.player());
    }

    private CaptureContext contextLocked(boolean paused) {
        return new CaptureContext(sampleSequence, logicalTimeLocked(paused));
    }

    private ClientStateSnapshot.LogicalTime logicalTimeLocked(boolean paused) {
        return new ClientStateSnapshot.LogicalTime(logicalTick, logicalNanos, paused);
    }

    private void updateClockLocked(boolean paused) {
        long now = monotonicClock.getAsLong();
        if (lastMonotonicNanos != INITIAL_CLOCK && !lastPaused && !paused && now >= lastMonotonicNanos) {
            logicalNanos = saturatingAdd(logicalNanos, now - lastMonotonicNanos);
        }
        lastMonotonicNanos = now;
        lastPaused = paused;
        pauseKnown = true;
    }

    private void cancel(Subscription subscription) {
        synchronized (lock) {
            cancelLocked(subscription);
            clearIfUnusedLocked();
        }
    }

    private void cancelLocked(Subscription subscription) {
        if (subscription.cancelled.compareAndSet(false, true)) {
            subscriptions.remove(subscription.id);
        }
    }

    private void clearIfUnusedLocked() {
        if (!subscriptions.isEmpty()) return;
        pending.clear();
        pendingResync = ClientStateBatch.ResyncReason.NONE;
        droppedSamples = 0L;
        latest = null;
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    record Metrics(int activeSubscriptions, int pendingSamples, long queuePeak, long queueOverflowCount,
                   long droppedSamples, long staleSamplesRejected, long deliveredBatchCount,
                   long deliveredSprintTransitionCount) { }

    private record CaptureContext(long sequence, ClientStateSnapshot.LogicalTime time) { }
    private record Delivery(Subscription subscription, ClientStateBatch batch) { }

    private final class OwnerService implements ClientStateService {
        private final long ownerToken;

        private OwnerService(long ownerToken) {
            this.ownerToken = ownerToken;
        }

        @Override public Optional<ClientStateSnapshot> current() { return ClientStateHub.this.current(); }

        @Override
        public ClientStateSubscription subscribe(Consumer<ClientStateBatch> listener) {
            Objects.requireNonNull(listener, "listener");
            synchronized (lock) {
                long id = ++nextSubscriptionId;
                Subscription subscription = new Subscription(id, ownerToken, listener);
                if (latest != null) {
                    subscription.initialSnapshot = latest;
                    subscription.lastSequence = latest.sampleSequence();
                    subscription.sessionGeneration = latest.world().generation();
                    subscription.playerIdentity = latest.player().valid() ? latest.player().identity() : null;
                    subscription.sprinting = latest.player().valid() && latest.player().sprinting();
                } else {
                    subscription.lastSequence = sampleSequence;
                }
                subscriptions.put(id, subscription);
                return subscription;
            }
        }
    }

    private final class Subscription implements ClientStateSubscription {
        private final long id;
        private final long ownerToken;
        private final Consumer<ClientStateBatch> listener;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private boolean initialPending = true;
        private ClientStateSnapshot initialSnapshot;
        private long lastSequence;
        private long sessionGeneration = -1L;
        private ClientStateSnapshot.EntityIdentity playerIdentity;
        private boolean sprinting;

        private Subscription(long id, long ownerToken, Consumer<ClientStateBatch> listener) {
            this.id = id;
            this.ownerToken = ownerToken;
            this.listener = listener;
        }

        @Override public boolean cancel() {
            boolean wasActive = !cancelled.get();
            ClientStateHub.this.cancel(this);
            return wasActive;
        }

        @Override public boolean isCancelled() { return cancelled.get(); }
    }
}
