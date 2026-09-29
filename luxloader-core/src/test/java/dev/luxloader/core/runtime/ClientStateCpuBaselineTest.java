package dev.luxloader.core.runtime;

import dev.luxloader.api.state.ClientStateBatch;
import dev.luxloader.api.state.ClientStateSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in CPU baseline; run with -Dluxloader.test.clientStateBaseline=true. */
class ClientStateCpuBaselineTest {
    private static final int DEFAULT_WARMUP = 10_000;
    private static final int DEFAULT_OPERATIONS = 20_000;
    private static final int DEFAULT_REPEATS = 5;
    private static final long SESSION = 1L;
    private static final ClientStateSnapshot.WorldSession WORLD =
            new ClientStateSnapshot.WorldSession(SESSION, true, "minecraft:overworld");
    private static final ClientStateSnapshot.Environment ENVIRONMENT =
            new ClientStateSnapshot.Environment(true, "minecraft:overworld", 6000L, 0f, 0f,
                    new ClientStateSnapshot.BiomeSample(true, "minecraft:plains", 1, 64, 2));
    private static final ClientStateSnapshot.EntityIdentity PLAYER_IDENTITY =
            new ClientStateSnapshot.EntityIdentity(SESSION, 7, "fixed-player", 1L);
    private static final long[] FIXED_INPUT = new long[256];
    private static volatile long blackhole;

    static {
        for (int i = 0; i < FIXED_INPUT.length; i++) FIXED_INPUT[i] = 0x9e3779b97f4a7c15L * (i + 1L);
    }

    @Test
    void reportsFourFixedInputBaselinesAndStressScenarios() {
        assumeTrue(Boolean.getBoolean("luxloader.test.clientStateBaseline")
                        || "1".equals(System.getenv("LUXLOADER_CLIENT_STATE_BASELINE")),
                "Set -Dluxloader.test.clientStateBaseline=true to run the CPU baseline");
        int warmup = Integer.getInteger("luxloader.clientStateBaseline.warmup", DEFAULT_WARMUP);
        int operations = Integer.getInteger("luxloader.clientStateBaseline.operations", DEFAULT_OPERATIONS);
        int repeats = Integer.getInteger("luxloader.clientStateBaseline.repeats", DEFAULT_REPEATS);
        if (warmup < 1 || operations < 100 || repeats < 1) {
            throw new IllegalArgumentException("Baseline requires warmup >= 1, operations >= 100, repeats >= 1");
        }

        System.out.printf("[Client state CPU baseline] java=%s vm=%s warmup=%d operations=%d repeats=%d%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                warmup, operations, repeats);
        for (Kind kind : Kind.values()) {
            List<Round> rounds = new ArrayList<>();
            for (int repeat = 0; repeat < repeats; repeat++) {
                rounds.add(measure(kind, warmup, operations));
            }
            report(kind.label, rounds);
        }
        reportBurstAndRecovery();
        reportSlowSubscriber();
    }

    private static Round measure(Kind kind, int warmup, int operations) {
        Scenario scenario = new Scenario(kind);
        long checksum = 0L;
        for (int i = 0; i < warmup; i++) checksum += scenario.runOne(i);

        ClientStateHub.Metrics before = scenario.metrics();
        long[] latencies = new long[operations];
        com.sun.management.ThreadMXBean allocationBean = allocationBean();
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocatedBytes(allocationBean, threadId);
        long wallStart = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            long start = System.nanoTime();
            checksum += scenario.runOne(warmup + i);
            latencies[i] = System.nanoTime() - start;
        }
        long wallNanos = System.nanoTime() - wallStart;
        long allocatedAfter = allocatedBytes(allocationBean, threadId);
        blackhole = checksum;

        ClientStateHub.Metrics after = scenario.metrics();
        Arrays.sort(latencies);
        double allocatedPerOperation = allocatedBefore < 0 || allocatedAfter < allocatedBefore
                ? Double.NaN : (double) (allocatedAfter - allocatedBefore) / operations;
        return new Round(percentileNanos(latencies, .50), percentileNanos(latencies, .95),
                percentileNanos(latencies, .99), allocatedPerOperation, wallNanos,
                after.queuePeak(), after.queueOverflowCount() - before.queueOverflowCount(),
                after.deliveredBatchCount() - before.deliveredBatchCount(),
                after.deliveredSprintTransitionCount() - before.deliveredSprintTransitionCount());
    }

    private static void report(String label, List<Round> rounds) {
        System.out.printf("[Client state CPU baseline] scenario=%s p50_us=%.3f p95_us=%.3f p99_us=%.3f "
                        + "allocated_B_op=%.1f wall_ms=%.2f queue_peak=%d overflow=%d batches=%d transitions=%d%n",
                label, median(rounds, Round::p50Nanos) / 1_000d,
                median(rounds, Round::p95Nanos) / 1_000d,
                median(rounds, Round::p99Nanos) / 1_000d,
                median(rounds, Round::allocatedPerOperation),
                median(rounds, round -> round.wallNanos / 1_000_000d),
                rounds.stream().mapToLong(Round::queuePeak).max().orElse(0L),
                rounds.stream().mapToLong(Round::overflows).sum(),
                rounds.stream().mapToLong(Round::batches).sum(),
                rounds.stream().mapToLong(Round::transitions).sum());
    }

    private static void reportBurstAndRecovery() {
        AtomicLong clock = new AtomicLong();
        ClientStateHub burstHub = new ClientStateHub(() -> clock.getAndAdd(1L), 16, ignored -> { });
        List<ClientStateBatch> burstBatches = new ArrayList<>();
        burstHub.serviceForOwner(1L).subscribe(burstBatches::add);
        BiFunction<Long, ClientStateSnapshot.LogicalTime, ClientStateSnapshot> factory =
                (sequence, time) -> snapshot(sequence, time, false);
        burstHub.safePoint(false, SESSION, factory);
        ClientStateHub.Metrics beforeBurst = burstHub.metrics();
        for (int i = 0; i < 10; i++) burstHub.clientTick(false, factory);
        ClientStateHub.Metrics pendingBurst = burstHub.metrics();
        burstHub.safePoint(false, SESSION, factory);
        ClientStateBatch coalesced = burstBatches.getLast();
        System.out.printf("[Client state CPU baseline] burst_ticks=10 queue_peak=%d overflow=%d "
                        + "coalesced_batches=%d sequence_span=%d..%d%n",
                pendingBurst.queuePeak(), pendingBurst.queueOverflowCount() - beforeBurst.queueOverflowCount(),
                burstBatches.size() - 1, coalesced.firstSequence(), coalesced.lastSequence());

        ClientStateHub overflowHub = new ClientStateHub(() -> clock.getAndIncrement(), 4, ignored -> { });
        List<ClientStateBatch> overflowBatches = new ArrayList<>();
        overflowHub.serviceForOwner(2L).subscribe(overflowBatches::add);
        overflowHub.safePoint(false, SESSION, factory);
        for (int i = 0; i < 40; i++) overflowHub.clientTick(false, factory);
        ClientStateHub.Metrics overflowPending = overflowHub.metrics();
        overflowHub.safePoint(false, SESSION, factory);
        ClientStateBatch recovery = overflowBatches.getLast();
        System.out.printf("[Client state CPU baseline] overflow_burst_ticks=40 capacity=4 queue_peak=%d "
                        + "overflow=%d pending_dropped=%d recovery_reason=%s recovery_batch_dropped=%d%n",
                overflowPending.queuePeak(), overflowPending.queueOverflowCount(), overflowPending.droppedSamples(),
                recovery.resyncReason(), recovery.droppedSamples());

        AtomicLong logicalClock = new AtomicLong();
        ClientStateHub pauseHub = new ClientStateHub(() -> logicalClock.getAndAdd(25_000_000L), 8, ignored -> { });
        List<ClientStateBatch> pauseBatches = new ArrayList<>();
        pauseHub.serviceForOwner(3L).subscribe(pauseBatches::add);
        pauseHub.safePoint(false, SESSION, factory);
        pauseHub.clientTick(false, factory);
        pauseHub.safePoint(false, SESSION, factory);
        pauseHub.clientTick(false, factory);
        pauseHub.safePoint(false, SESSION, factory);
        int beforePause = pauseBatches.size();
        pauseHub.safePoint(true, SESSION, factory);
        pauseHub.safePoint(true, SESSION, factory);
        pauseHub.safePoint(false, SESSION, factory);
        System.out.printf("[Client state CPU baseline] pause_recovery_boundaries=%d final_logic_tick=%d "
                        + "final_elapsed_ms=%.3f queue_peak=%d overflow=%d%n",
                pauseBatches.size() - beforePause, pauseBatches.getLast().snapshot().time().tick(),
                pauseBatches.getLast().snapshot().time().elapsedNanos() / 1_000_000d,
                pauseHub.metrics().queuePeak(), pauseHub.metrics().queueOverflowCount());
    }

    private static void reportSlowSubscriber() {
        AtomicLong clock = new AtomicLong();
        ClientStateHub hub = new ClientStateHub(() -> clock.getAndIncrement(), 16, ignored -> { });
        BiFunction<Long, ClientStateSnapshot.LogicalTime, ClientStateSnapshot> factory =
                (sequence, time) -> snapshot(sequence, time, false);
        hub.serviceForOwner(4L).subscribe(batch -> {
            if (batch.initial()) return;
            long until = System.nanoTime() + 500_000L;
            while (System.nanoTime() < until) blackhole++;
        });
        hub.safePoint(false, SESSION, factory);
        long[] latencies = new long[21];
        for (int i = 0; i < latencies.length; i++) {
            hub.clientTick(false, factory);
            long start = System.nanoTime();
            hub.safePoint(false, SESSION, factory);
            latencies[i] = System.nanoTime() - start;
        }
        Arrays.sort(latencies);
        ClientStateHub.Metrics metrics = hub.metrics();
        System.out.printf("[Client state CPU baseline] slow_subscriber_spin_ns=500000 safe_point_p50_us=%.3f "
                        + "p95_us=%.3f p99_us=%.3f queue_peak=%d overflow=%d%n",
                percentileNanos(latencies, .50) / 1_000d, percentileNanos(latencies, .95) / 1_000d,
                percentileNanos(latencies, .99) / 1_000d, metrics.queuePeak(), metrics.queueOverflowCount());
    }

    private static ClientStateSnapshot snapshot(long sequence, ClientStateSnapshot.LogicalTime time,
                                                 boolean sprinting) {
        ClientStateSnapshot.Player player = new ClientStateSnapshot.Player(true, PLAYER_IDENTITY,
                0d, 64d, 0d, 0f, 0f, 0d, 0d, 0d, true, false,
                sprinting, false, false, ClientStateSnapshot.Pose.STANDING,
                true, 20f, 20f, true, 0,
                ClientStateSnapshot.ItemDescription.EMPTY, ClientStateSnapshot.ItemDescription.EMPTY);
        return new ClientStateSnapshot(sequence, time, WORLD, ENVIRONMENT, player);
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation)
                || !allocation.isThreadAllocatedMemorySupported()) return null;
        if (!allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
        return allocation;
    }

    private static long allocatedBytes(com.sun.management.ThreadMXBean bean, long threadId) {
        return bean == null ? -1L : bean.getThreadAllocatedBytes(threadId);
    }

    private static long percentileNanos(long[] sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }

    private static double median(List<Round> rounds, java.util.function.ToDoubleFunction<Round> value) {
        double[] values = rounds.stream().mapToDouble(value).sorted().toArray();
        int middle = values.length / 2;
        return values.length % 2 == 0 ? (values[middle - 1] + values[middle]) / 2d : values[middle];
    }

    private enum Kind {
        NO_PLUGIN("no-plugin"),
        PLUGIN_NO_SUBSCRIPTION("plugin-no-subscription"),
        STATE_SUBSCRIPTION("state-subscription"),
        TYPICAL_EVENT("typical-event");

        private final String label;

        Kind(String label) { this.label = label; }
    }

    private static final class Scenario {
        private final Kind kind;
        private final ClientStateHub hub;
        private final BiFunction<Long, ClientStateSnapshot.LogicalTime, ClientStateSnapshot> factory;
        private long inputIndex;

        private Scenario(Kind kind) {
            this.kind = kind;
            if (kind == Kind.NO_PLUGIN) {
                hub = null;
                factory = null;
                return;
            }
            AtomicLong clock = new AtomicLong();
            hub = new ClientStateHub(() -> clock.getAndAdd(8_333_333L), ClientStateHub.DEFAULT_QUEUE_CAPACITY,
                    ignored -> { });
            factory = (sequence, time) -> snapshot(sequence, time,
                    kind == Kind.TYPICAL_EVENT && (inputIndex % 240L) >= 120L);
            if (kind == Kind.STATE_SUBSCRIPTION || kind == Kind.TYPICAL_EVENT) {
                hub.serviceForOwner(10L).subscribe(batch -> { });
            }
        }

        private long runOne(int inputIndex) {
            this.inputIndex = inputIndex;
            if (hub != null) {
                hub.clientTick(false, factory);
                hub.safePoint(false, SESSION, factory);
            }
            return FIXED_INPUT[inputIndex & (FIXED_INPUT.length - 1)];
        }

        private ClientStateHub.Metrics metrics() {
            return hub == null ? new ClientStateHub.Metrics(0, 0, 0, 0, 0, 0, 0, 0) : hub.metrics();
        }
    }

    private record Round(long p50Nanos, long p95Nanos, long p99Nanos, double allocatedPerOperation,
                         long wallNanos, long queuePeak, long overflows, long batches, long transitions) { }
}
