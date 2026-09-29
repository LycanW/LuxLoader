package dev.luxloader.core.runtime;

import dev.luxloader.api.event.BehaviorEvent;
import dev.luxloader.api.event.EventValue;
import dev.luxloader.api.state.ClientStateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Controlled core-only CPU evidence; this is not an end-to-end Minecraft benchmark. */
class ClientEventHubCpuEvidenceTest {
    private static final int WARMUP_ITERATIONS = 1_000;
    private static final int MEASURED_ITERATIONS = 10_000;
    private static volatile ClientEventHub currentHub;

    @Test
    @DisplayName("Controlled event capture CPU paths preserve the idle gate and measure one subscriber")
    void controlledCaptureCpuPaths() {
        ClientStateSnapshot snapshot = snapshot();
        ClientEventHub idleHub = new ClientEventHub(256, ignored -> { });
        currentHub = idleHub;
        AtomicInteger idlePayloadsBuilt = new AtomicInteger();
        AtomicInteger idleSignalsRejected = new AtomicInteger();
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            if (!idleHub.recordBehavior(1L, BehaviorEvent.Kind.ATTACK,
                    BehaviorEvent.Phase.REQUESTED, BehaviorEvent.Source.LOCAL_INTENT, () -> {
                        idlePayloadsBuilt.incrementAndGet();
                        return payload();
                    }, Optional.of(snapshot))) idleSignalsRejected.incrementAndGet();
        }

        ClientEventHub activeHub = new ClientEventHub(256, ignored -> { });
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger activePayloadsBuilt = new AtomicInteger();
        activeHub.serviceForOwner(1L, "example:cpu", Optional::empty)
                .subscribe(batch -> delivered.addAndGet(batch.events().size()));
        activeHub.safePoint(snapshot);
        currentHub = activeHub;
        Runnable activeEvent = () -> {
            currentHub.recordBehavior(1L, BehaviorEvent.Kind.ATTACK,
                    BehaviorEvent.Phase.REQUESTED, BehaviorEvent.Source.LOCAL_INTENT, () -> {
                        activePayloadsBuilt.incrementAndGet();
                        return payload();
                    }, Optional.of(snapshot));
            activeHub.safePoint(snapshot);
        };
        run(activeEvent, WARMUP_ITERATIONS);
        delivered.set(0);
        activePayloadsBuilt.set(0);
        long activeCpuNanos = measure(activeEvent, MEASURED_ITERATIONS);

        assertEquals(0, idlePayloadsBuilt.get());
        assertEquals(MEASURED_ITERATIONS, idleSignalsRejected.get());
        assertEquals(MEASURED_ITERATIONS, activePayloadsBuilt.get());
        assertEquals(MEASURED_ITERATIONS, delivered.get());
        long activeNsPerEvent = activeCpuNanos / MEASURED_ITERATIONS;
        System.out.printf("T3_EVENT_CPU iterations=%d idle_signals_rejected=%d idle_payload_factories=%d subscribed_cpu_ns=%d subscribed_ns_per_event=%d measured_with=%s%n",
                MEASURED_ITERATIONS, idleSignalsRejected.get(), idlePayloadsBuilt.get(),
                activeCpuNanos, activeNsPerEvent,
                cpuClockAvailable() ? "thread-cpu-time" : "wall-clock-fallback");
        assertTrue(activeCpuNanos >= 0L);
    }

    private static void run(Runnable action, int iterations) {
        for (int i = 0; i < iterations; i++) action.run();
    }

    private static long measure(Runnable action, int iterations) {
        long start = cpuTime();
        run(action, iterations);
        return Math.max(0L, cpuTime() - start);
    }

    private static long cpuTime() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
                ? bean.getCurrentThreadCpuTime() : System.nanoTime();
    }

    private static boolean cpuClockAvailable() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled();
    }

    private static EventValue.ObjectValue payload() {
        return new EventValue.ObjectValue(Map.of("targetEntityId", new EventValue.IntegerNumber(4L)));
    }

    private static ClientStateSnapshot snapshot() {
        return new ClientStateSnapshot(1L, new ClientStateSnapshot.LogicalTime(1L, 50_000_000L, false),
                new ClientStateSnapshot.WorldSession(1L, true, "minecraft:overworld"),
                new ClientStateSnapshot.Environment(false, "", 0L, 0f, 0f,
                        ClientStateSnapshot.BiomeSample.UNAVAILABLE), ClientStateSnapshot.Player.UNAVAILABLE);
    }
}
