package dev.luxloader.api.state;

/** A plugin-owned client-state subscription. */
public interface ClientStateSubscription extends AutoCloseable {
    /** Cancel delivery. Repeated calls are harmless. */
    boolean cancel();

    /** Whether this subscription has been cancelled or its plugin instance unloaded. */
    boolean isCancelled();

    @Override
    default void close() {
        cancel();
    }
}
