package dev.luxloader.core.runtime;

import dev.luxloader.api.resource.*;
import dev.luxloader.api.resource.ResourcePreparationService.*;
import dev.luxloader.api.scene.ResourceAccess;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePreparationHubTest {
    @Test void scratchStaysChargedUntilCompletionAndOnlyTheResultRemainsChargedAfterwards() throws Exception {
        Source source = new Source(); CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        try (var hub = new ResourcePreparationHub(() -> source, SMALL); var scope = hub.serviceForOwner(1).openScope()) {
            var task = scope.submit(List.of(KEY), input -> {
                input.reserveDecodedBytes(32); input.retainDecodedBytes(8); entered.countDown(); await(leave); return 1;
            });
            await(entered); assertEquals(32, scope.metrics().decodedBytes()); leave.countDown(); terminal(task);
            // A following job on the same single worker proves the first finally block completed.
            terminal(scope.submit(List.of(KEY), input -> 2));
            assertEquals(8, scope.metrics().decodedBytes()); assertEquals(32, scope.metrics().peakDecodedBytes());
            task.close(); assertEquals(0, scope.metrics().decodedBytes());
        } finally { leave.countDown(); }
    }
    @Test void streamCloseFailuresCannotLeakWorkSlotsOrPreventOtherStreamsFromClosing() {
        var closes = new AtomicInteger();
        ResourceAccess source = new ResourceAccess() {
            public long revision() { return 1; }
            public Optional<byte[]> read(String ns, String path) { throw new AssertionError(); }
            public Optional<InputStream> open(ResourceKey key) {
                return Optional.of(new ByteArrayInputStream(new byte[]{1}) {
                    public void close() { closes.incrementAndGet(); throw new IllegalStateException("Broken stream cleanup"); }
                });
            }
        };
        var hub = new ResourcePreparationHub(() -> source, SMALL);
        try (var scope = hub.serviceForOwner(1).openScope()) {
            var first = terminal(scope.submit(List.of(KEY, new ResourceKey("test", "other.png")), input -> {
                input.reserveDecodedBytes(8); return 1;
            }));
            first.close();
            assertEquals(2, terminal(scope.submit(List.of(KEY), input -> 2)).result().orElseThrow());
            assertEquals(3, closes.get()); assertEquals(0, scope.metrics().decodedBytes());
            assertEquals(3, scope.metrics().streamCleanupFailures());
        }
        assertDoesNotThrow(hub::close); assertEquals(3, closes.get());
    }
    static final ResourceKey KEY = new ResourceKey("test", "textures/winner.png");
    static final Limits SMALL = new Limits(1, 1, 4, 16, 2, 16, 32, 32, 64);

    static class Source implements ResourceAccess {
        volatile ResourceState state = new ResourceState(1, ResourceState.Phase.READY);
        final AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
        final AtomicReference<Thread> lookupThread = new AtomicReference<>(), readThread = new AtomicReference<>();
        byte[] bytes = {7, 8};
        Runnable duringOpen = () -> { };
        @Override public long revision() { return state.generation(); }
        @Override public ResourceState state() { return state; }
        @Override public Optional<byte[]> read(String ns, String path) { throw new AssertionError("Modern source must stream"); }
        @Override public Optional<InputStream> open(ResourceKey key) {
            lookupThread.set(Thread.currentThread()); opened.incrementAndGet(); duringOpen.run();
            return Optional.of(new ByteArrayInputStream(bytes) {
                @Override public synchronized int read(byte[] b, int off, int len) {
                    readThread.set(Thread.currentThread()); return super.read(b, off, len);
                }
                @Override public void close() { closed.incrementAndGet(); }
            });
        }
    }
    static <T> Task<T> terminal(Task<T> task) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (task.status() == Status.PENDING && System.nanoTime() < until) Thread.yield();
        assertNotEquals(Status.PENDING, task.status(), "Worker must complete");
        return task;
    }
    static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Controlled worker boundary reached");
    }

    @Test void lookupStaysOnHostWhileReadingAndDecodingRunOnWorker() {
        Source source = new Source(); Thread host = Thread.currentThread();
        try (var hub = new ResourcePreparationHub(() -> source, SMALL); var scope = hub.serviceForOwner(1).openScope()) {
            var task = scope.submit(List.of(KEY), input -> {
                assertNotSame(host, Thread.currentThread());
                assertTrue(input.bytes(KEY).orElseThrow().isReadOnly());
                input.reserveDecodedBytes(4); return (int) input.bytes(KEY).orElseThrow().get();
            });
            assertEquals(7, terminal(task).result().orElseThrow());
            assertSame(host, source.lookupThread.get()); assertNotSame(host, source.readThread.get());
            assertEquals(1, source.closed.get());
            task.close(); assertEquals(0, scope.metrics().decodedBytes());
        }
        assertEquals(1, source.closed.get());
    }

    @Test void aGenerationChangeDuringAcquisitionClosesTheStreamExactlyOnce() {
        Source source = new Source(); source.duringOpen = () -> source.state = new ResourceState(2, ResourceState.Phase.RELOADING);
        try (var hub = new ResourcePreparationHub(() -> source, SMALL); var scope = hub.serviceForOwner(1).openScope()) {
            var task = scope.submit(List.of(KEY), input -> { fail("Obsolete bytes must not decode"); return 0; });
            assertEquals(Status.CANCELLED, task.status()); assertEquals(1, source.closed.get());
        }
        assertEquals(1, source.closed.get());
    }

    @Test void lateDecodeCannotPublishAfterReloadAndMemoryRemainsChargedUntilWorkerReturns() throws Exception {
        Source source = new Source(); CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        try (var hub = new ResourcePreparationHub(() -> source, SMALL); var scope = hub.serviceForOwner(1).openScope()) {
            var old = scope.submit(List.of(KEY), input -> { input.reserveDecodedBytes(16); entered.countDown(); await(leave); return 7; });
            await(entered);
            source.state = new ResourceState(2, ResourceState.Phase.RELOADING); hub.refresh();
            assertEquals(Status.CANCELLED, old.status()); assertTrue(old.result().isEmpty());
            assertEquals(16, scope.metrics().decodedBytes(), "Cancelled running decoders still own their allocation");
            leave.countDown();
            source.state = new ResourceState(3, ResourceState.Phase.READY);
            var current = scope.submit(List.of(KEY), input -> 9);
            assertEquals(9, terminal(current).result().orElseThrow());
            assertTrue(old.result().isEmpty()); assertEquals(0, scope.metrics().decodedBytes());
        } finally { leave.countDown(); }
    }

    @Test void closedOwnerAndPipelineScopesCannotKeepResultsOrSubmitMoreWork() {
        Source source = new Source();
        try (var hub = new ResourcePreparationHub(() -> source, SMALL)) {
            var owner1 = hub.serviceForOwner(1).openScope(); var owner2 = hub.serviceForOwner(2).openScope();
            var old = owner1.submit(List.of(KEY), input -> 7); terminal(old);
            hub.releaseOwner(1);
            assertTrue(owner1.isClosed()); assertTrue(old.result().isEmpty());
            assertEquals(Failure.CLOSED, owner1.submit(List.of(KEY), input -> 8).failure());
            assertFalse(owner2.isClosed()); hub.closeScopes(); assertTrue(owner2.isClosed());
            try (var replacement = hub.serviceForOwner(1).openScope()) {
                assertEquals(9, terminal(replacement.submit(List.of(KEY), input -> 9)).result().orElseThrow());
            }
        }
    }

    @Test void worldChangeInvalidatesCompletedResultsEvenWithUnchangedResourceRevision() {
        Source source = new Source();
        try (var hub = new ResourcePreparationHub(() -> source, SMALL); var scope = hub.serviceForOwner(1).openScope()) {
            var task = terminal(scope.submit(List.of(KEY), input -> 1));
            hub.worldSession(2);
            assertTrue(task.result().isEmpty()); assertEquals(2, scope.generation().world());
            assertEquals(1, terminal(scope.submit(List.of(KEY), input -> 1)).result().orElseThrow());
        }
    }

    @Test void queueRejectsBeforeOpeningAnotherFileAndCancelledQueuedWorkClosesItsLease() throws Exception {
        Source source = new Source(); CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        try (var hub = new ResourcePreparationHub(() -> source, SMALL); var scope = hub.serviceForOwner(1).openScope()) {
            var running = scope.submit(List.of(KEY), input -> { entered.countDown(); await(leave); return 1; });
            await(entered);
            var queued = scope.submit(List.of(KEY), input -> 2);
            var rejected = scope.submit(List.of(KEY), input -> 3);
            assertEquals(Status.REJECTED, rejected.status()); assertEquals(Failure.BACKPRESSURE, rejected.failure());
            assertEquals(2, source.opened.get()); queued.close();
            assertEquals(2, source.closed.get()); leave.countDown(); terminal(running);
        } finally { leave.countDown(); }
    }

    @Test void byteLimitAndDecoderReservationRejectOversizedResources() {
        Source source = new Source(); source.bytes = new byte[17];
        try (var hub = new ResourcePreparationHub(() -> source, SMALL); var scope = hub.serviceForOwner(1).openScope()) {
            var file = terminal(scope.submit(List.of(KEY), input -> { fail("Oversized file never reaches decoder"); return 1; }));
            assertEquals(Failure.LIMIT, file.failure());
            source.bytes = new byte[2];
            var decoded = terminal(scope.submit(List.of(KEY), input -> { input.reserveDecodedBytes(33); return 1; }));
            assertEquals(Failure.LIMIT, decoded.failure()); assertEquals(0, scope.metrics().decodedBytes());
        }
    }

    @Test void combinedResidentBudgetIncludesPreviouslyCompletedResults() {
        Source source = new Source();
        try (var hub = new ResourcePreparationHub(() -> source, SMALL); var scope = hub.serviceForOwner(1).openScope()) {
            Decoder<Integer> decoder = input -> { input.reserveDecodedBytes(32); return 1; };
            var first = terminal(scope.submit(List.of(KEY), decoder));
            var second = terminal(scope.submit(List.of(KEY), decoder));
            assertEquals(Status.READY, first.status()); assertEquals(Status.READY, second.status());
            assertEquals(Failure.LIMIT, terminal(scope.submit(List.of(KEY), decoder)).failure());
            first.close(); assertEquals(Status.READY, terminal(scope.submit(List.of(KEY), decoder)).status());
            assertEquals(64, scope.metrics().peakDecodedBytes());
        }
    }

    @Test void legacyReadAndMissingSemanticsRemainCompatible() {
        ResourceAccess legacy = new ResourceAccess() {
            public long revision() { return 3; }
            public Optional<byte[]> read(String ns, String path) { return path.equals(KEY.path()) ? Optional.of(new byte[]{5}) : Optional.empty(); }
        };
        var missing = new ResourceKey("test", "missing.png");
        try (var hub = new ResourcePreparationHub(() -> legacy, SMALL); var scope = hub.serviceForOwner(1).openScope()) {
            var task = scope.submit(List.of(KEY, missing), input -> {
                assertTrue(input.bytes(missing).isEmpty()); return (int) input.bytes(KEY).orElseThrow().get();
            });
            assertEquals(5, terminal(task).result().orElseThrow()); assertEquals(3, task.generation().resource());
        }
    }

    @Test void failingDecoderIsIsolatedAndUnavailableGenerationDoesNotOpenFiles() {
        Source source = new Source();
        try (var hub = new ResourcePreparationHub(() -> source, SMALL); var scope = hub.serviceForOwner(1).openScope()) {
            assertEquals(Failure.DECODE, terminal(scope.submit(List.of(KEY), input -> { throw new IllegalArgumentException("broken png"); })).failure());
            source.state = new ResourceState(2, ResourceState.Phase.FAILED);
            assertEquals(Failure.UNAVAILABLE, scope.submit(List.of(KEY), input -> 1).failure());
            assertEquals(1, source.opened.get());
            source.state = new ResourceState(3, ResourceState.Phase.READY);
            assertEquals(2, terminal(scope.submit(List.of(KEY), input -> 2)).result().orElseThrow());
        }
    }
}
