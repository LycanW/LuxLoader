package dev.luxloader.api.event;

import java.util.Objects;
import java.util.function.Consumer;

/** Client behavior and plugin-defined events delivered only from the host's client safe update point. */
public interface ClientEventService {
    ClientEventService EMPTY = new ClientEventService() {
        @Override public ClientEventSubscription subscribe(Consumer<ClientEventBatch> listener) {
            throw new UnsupportedOperationException("Client events are unavailable");
        }
        @Override public ClientEventSubscription subscribeCustom(CustomEventType type,
                                                                  Consumer<ClientEventBatch> listener) {
            throw new UnsupportedOperationException("Client events are unavailable");
        }
        @Override public CustomEventRegistration registerCustomType(EventTypeId id, int version) {
            throw new UnsupportedOperationException("Client events are unavailable");
        }
        @Override public void publish(CustomEventType type, EventValue.ObjectValue payload) {
            throw new UnsupportedOperationException("Client events are unavailable");
        }
    };

    /** Receive built-in behavior events and custom events from every registered type. */
    ClientEventSubscription subscribe(Consumer<ClientEventBatch> listener);

    /**
     * Receive batches filtered to one registered custom type. Initial and resynchronization batches are
     * retained so a type-specific listener can detect queue gaps.
     */
    ClientEventSubscription subscribeCustom(CustomEventType type, Consumer<ClientEventBatch> listener);

    /** Register a type under the calling plugin's namespace. */
    CustomEventRegistration registerCustomType(EventTypeId id, int version);

    /** Publish through a registration owned by the calling plugin instance. */
    void publish(CustomEventType type, EventValue.ObjectValue payload);
}
