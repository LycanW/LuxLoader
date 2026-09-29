package dev.luxloader.api.event;

/** Registration of one custom event type by its namespace owner. */
public interface CustomEventRegistration extends AutoCloseable {
    CustomEventType type();
    boolean cancel();
    boolean isCancelled();
    @Override default void close() { cancel(); }
}
