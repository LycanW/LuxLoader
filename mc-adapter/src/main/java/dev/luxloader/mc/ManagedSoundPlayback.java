package dev.luxloader.mc;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Guards host decode completion and the later sound-executor consumption independently. A streaming
 * lease is closed if cancellation wins before attachment; shared static buffers remain host-owned.
 * The state lock orders cancellation/pause with the final attach/play action, without game ticks.
 */
public final class ManagedSoundPlayback {
    public interface DecodedLease {
        /** Transfer a stream to its channel, or attach a borrowed host static buffer. */
        void attach();
        /** Close only a stream that was never transferred. Shared cached buffers must not be closed. */
        void discard();
    }

    private final Object lock = new Object();
    private boolean cancelled, paused, attached, started;

    public boolean cancelled() { synchronized (lock) { return cancelled; } }

    /** Mark cancellation before scheduling native stop; queued and future completions see this state. */
    public boolean cancel() { synchronized (lock) { boolean changed = !cancelled; cancelled = true; return changed; } }

    public void markPaused(boolean paused) { synchronized (lock) { this.paused = paused; } }

    public void applyPause(Runnable pauseChannel, Runnable initialPlay, Runnable resumeChannel) {
        synchronized (lock) {
            if (cancelled || !attached) return;
            if (paused) pauseChannel.run();
            else {
                // A load completed during pause has an attached channel that has never been started.
                if (started) resumeChannel.run(); else initialPlay.run();
                started = true;
            }
        }
    }

    /** Runs on the actual sound executor, immediately before transferring a decoded asset. */
    public void consume(DecodedLease lease, Runnable play) {
        Objects.requireNonNull(lease); Objects.requireNonNull(play);
        synchronized (lock) {
            if (cancelled) { lease.discard(); return; }
            lease.attach(); attached = true;
            if (!paused) { play.run(); started = true; }
        }
    }

    public boolean started() { synchronized (lock) { return started; } }

    /**
     * Used by the production SoundEngine hook, not a separate fake playback path. The first check
     * rejects a cancelled IO result; the consume check rejects cancellation after executor enqueue.
     * releaseWithoutChannel handles an executor handle that has already been released by the host.
     */
    public <T> CompletableFuture<Void> whenDecoded(CompletableFuture<T> decoded, Consumer<T> enqueue,
                                                  Consumer<T> releaseWithoutChannel) {
        return decoded.thenAccept(value -> {
            synchronized (lock) {
                if (cancelled) { releaseWithoutChannel.accept(value); return; }
            }
            enqueue.accept(value);
        });
    }
}
