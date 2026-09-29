package dev.luxloader.mc;

import dev.luxloader.api.resource.ResourceState;
import java.util.concurrent.CancellationException;

/** Serializes reload completion without allowing an older reload to revive an obsolete resource stack. */
public final class ResourceReloadState {
    private volatile ResourceState state = new ResourceState(0, ResourceState.Phase.READY);
    public ResourceState current() { return state; }
    /** Covers both synchronous construction failure and asynchronous reload completion. */
    public <T> T track(java.util.function.Supplier<T> start,
                       java.util.function.Function<T, java.util.concurrent.CompletionStage<?>> completion) {
        long token = begin();
        try {
            T reload = start.get();
            completion.apply(reload).whenComplete((ignored, failure) -> finish(token, failure));
            return reload;
        } catch (RuntimeException | Error failure) {
            finish(token, failure);
            throw failure;
        }
    }
    public synchronized long begin() {
        state = new ResourceState(state.generation() + 1, ResourceState.Phase.RELOADING);
        return state.generation();
    }
    public synchronized boolean finish(long token, Throwable failure) {
        if (state.generation() != token || state.phase() != ResourceState.Phase.RELOADING) return false;
        ResourceState.Phase phase = failure == null ? ResourceState.Phase.READY
                : cancelled(failure) ? ResourceState.Phase.CANCELLED : ResourceState.Phase.FAILED;
        state = new ResourceState(token + 1, phase);
        return true;
    }
    public synchronized void close() {
        state = new ResourceState(state.generation() + 1, ResourceState.Phase.CLOSED);
    }
    private static boolean cancelled(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof CancellationException) return true;
        return false;
    }
}
