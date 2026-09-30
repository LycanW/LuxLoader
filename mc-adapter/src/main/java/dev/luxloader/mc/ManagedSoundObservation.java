package dev.luxloader.mc;

import dev.luxloader.api.presentation.PresentationService.HostSound;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Orders listener publication and suppresses managed feedback before the host copies a payload. */
public final class ManagedSoundObservation implements AutoCloseable {
    private long generation = -1;
    private boolean enabled, closed;
    private Consumer<HostSound> sink = ignored -> { };

    /** Returns whether the host must reconcile listener registration. Older publications are ignored. */
    public synchronized boolean configure(long generation, boolean enabled, Consumer<HostSound> sink) {
        if (closed || generation < this.generation) return false;
        this.generation = generation;
        this.sink = Objects.requireNonNull(sink);
        boolean changed = this.enabled != enabled;
        this.enabled = enabled;
        return changed;
    }

    public synchronized boolean enabled() { return enabled && !closed; }

    /** Runs no supplier for managed sounds, disabled observation or an unsupported host thread. */
    public void capture(boolean managed, boolean clientThread, Supplier<HostSound> payload) {
        Consumer<HostSound> currentSink;
        synchronized (this) {
            if (managed || !clientThread || !enabled || closed) return;
            currentSink = sink;
        }
        HostSound value = payload.get();
        if (value != null) currentSink.accept(value);
    }

    @Override public synchronized void close() { closed = true; enabled = false; sink = ignored -> { }; }
}
