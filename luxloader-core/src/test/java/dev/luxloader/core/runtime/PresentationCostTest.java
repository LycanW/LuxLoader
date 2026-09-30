package dev.luxloader.core.runtime;

import dev.luxloader.api.resource.ResourceState;
import dev.luxloader.api.state.ClientStateSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static dev.luxloader.api.presentation.PresentationService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in synthetic CPU cost evidence; no GPU work, game frames or audio device operations. */
class PresentationCostTest {
    private static volatile boolean demand;
    @Test void reportIdleMetadataAndEightRunningInstances() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("luxloader.test.presentationCost"), "CPU cost fixture is opt-in");
        try (var hub = new PresentationHub(error -> fail(error))) {
            measure("idle-demand", () -> demand = hub.needsState(), 128);
            var registration = PresentationHubTest.register(hub, 1, PresentationHubTest.TYPE,
                    (view, scene) -> List.of(PresentationHubTest.mesh(view)), PresentationHubTest.SOUND);
            measure("metadata-demand", () -> demand = hub.needsState(), 128);
            assertFalse(hub.needsState());
            var sound = new PresentationHubTest.SoundPort(); hub.attach(sound);
            for (int i = 0; i < 8; i++) registration.start(PresentationHubTest.request(0, Long.MAX_VALUE));
            var pipeline = new PresentationHubTest.Pipeline(); var scene = PresentationHubTest.scene();
            var resources = new ResourceState(0, ResourceState.Phase.READY); var clock = new AtomicLong();
            measure("eight-running-safe-point-and-contribution", () -> {
                var time = new ClientStateSnapshot.LogicalTime(0, clock.incrementAndGet(), false);
                hub.safePoint(PresentationHubTest.snapshot(1, time, 1, true), time, 1, resources, pipeline);
                assertEquals(8, hub.contribute(scene, pipeline).size());
            }, 1);
            assertEquals(8, sound.voices.size()); assertEquals(8, hub.metrics().peakHandles());
            assertEquals(0, hub.metrics().soundQueued());
        }
    }

    private static void measure(String name, Runnable action, int batch) {
        for (int i = 0; i < 10000; i++) for (int j = 0; j < batch; j++) action.run();
        int samples = 10000; var nanos = new long[samples];
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        boolean allocation = bean.isThreadAllocatedMemorySupported();
        if (allocation && !bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId(); long before = allocation ? bean.getThreadAllocatedBytes(thread) : 0;
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime(); for (int j = 0; j < batch; j++) action.run(); nanos[i] = System.nanoTime() - start;
        }
        long bytes = allocation ? bean.getThreadAllocatedBytes(thread) - before : -1;
        Arrays.sort(nanos);
        System.out.println("presentation-cpu " + name + " samples=" + samples + " batch=" + batch + " p50ns=" + (double)nanos[samples / 2] / batch
                + " p95ns=" + (double)nanos[(int)(samples * 0.95)] / batch + " p99ns=" + (double)nanos[(int)(samples * 0.99)] / batch
                + " allocatedBytesPerCall=" + (allocation ? (double)bytes / ((long)samples * batch) : "unavailable"));
    }
}
