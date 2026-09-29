package dev.luxloader.api.event;

/** Idempotently cancellable subscription owned by the calling plugin instance. */
public interface ClientEventSubscription extends AutoCloseable {
    boolean cancel();
    boolean isCancelled();
    @Override default void close() { cancel(); }
}
