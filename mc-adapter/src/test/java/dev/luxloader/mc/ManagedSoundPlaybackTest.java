package dev.luxloader.mc;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Production guard used by the SoundEngine hook; actual transformed channel coverage is separate. */
class ManagedSoundPlaybackTest {
    static class Lease implements ManagedSoundPlayback.DecodedLease {
        int attached, discarded;
        public void attach() { attached++; }
        public void discard() { discarded++; }
    }

    @Test void cancellationBeforeDecodeClosesTheUnconsumedLease() {
        var playback = new ManagedSoundPlayback(); var decoded = new CompletableFuture<Lease>(); var lease = new Lease();
        var enqueued = new AtomicInteger();
        var consumed = playback.whenDecoded(decoded, ignored -> enqueued.incrementAndGet(), Lease::discard);
        playback.cancel(); decoded.complete(lease); consumed.join();
        assertEquals(0, enqueued.get()); assertEquals(1, lease.discarded); assertEquals(0, lease.attached);
    }

    @Test void cancellationAfterEnqueueIsRecheckedAtTheFinalConsumer() {
        var playback = new ManagedSoundPlayback(); var decoded = new CompletableFuture<Lease>(); var lease = new Lease();
        var queue = new ArrayDeque<Runnable>(); var plays = new AtomicInteger();
        playback.whenDecoded(decoded, value -> queue.add(() -> playback.consume(value, plays::incrementAndGet)), Lease::discard);
        decoded.complete(lease); assertEquals(1, queue.size());
        playback.cancel(); queue.remove().run();
        assertEquals(0, plays.get()); assertEquals(1, lease.discarded); assertEquals(0, lease.attached);
    }

    @Test void pauseDuringDecodeAttachesWithoutStartingAndResumeStartsExactlyOnce() {
        var playback = new ManagedSoundPlayback(); var lease = new Lease(); var plays = new AtomicInteger(); var resumes = new AtomicInteger();
        playback.markPaused(true); playback.consume(lease, plays::incrementAndGet);
        assertEquals(1, lease.attached); assertEquals(0, plays.get());
        playback.markPaused(false); playback.applyPause(() -> fail("Not paused"), plays::incrementAndGet, resumes::incrementAndGet);
        playback.applyPause(() -> fail("Not paused"), plays::incrementAndGet, resumes::incrementAndGet);
        assertEquals(1, plays.get()); assertEquals(1, resumes.get()); assertTrue(playback.started());
    }

    @Test void cancelWhilePausedNeverStartsTheAttachedAssetOnResume() {
        var playback = new ManagedSoundPlayback(); var lease = new Lease(); var plays = new AtomicInteger();
        playback.markPaused(true); playback.consume(lease, plays::incrementAndGet); playback.cancel(); playback.markPaused(false);
        playback.applyPause(() -> fail("Cancelled"), plays::incrementAndGet, plays::incrementAndGet);
        assertEquals(0, plays.get()); assertEquals(1, lease.attached);
        assertTrue(playback.cancelled()); assertFalse(playback.cancel());
    }

    @Test void failedDecodeDoesNotEnqueueOrClaimAssetOwnership() {
        var playback = new ManagedSoundPlayback(); var decoded = new CompletableFuture<Lease>();
        var consumed = playback.whenDecoded(decoded, ignored -> fail("Failed decode cannot be consumed"), ignored -> fail("No asset was returned"));
        decoded.completeExceptionally(new java.io.IOException("Decode failed"));
        assertThrows(java.util.concurrent.CompletionException.class, consumed::join);
    }
}
