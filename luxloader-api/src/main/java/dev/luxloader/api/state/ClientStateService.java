package dev.luxloader.api.state;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Read-only stream of immutable client observations. A subscription's first callback establishes
 * its baseline. If the cached starting boundary is invalidated by a session change or history gap
 * before delivery, the latest valid sample becomes that baseline; transitions before it are not
 * emitted. Later batches are delivered on the host's safe update point.
 */
public interface ClientStateService {
    ClientStateService EMPTY = new ClientStateService() {
        @Override public Optional<ClientStateSnapshot> current() { return Optional.empty(); }
        @Override public ClientStateSubscription subscribe(Consumer<ClientStateBatch> listener) {
            throw new UnsupportedOperationException("Client state is unavailable");
        }
    };

    /** Latest observation retained while at least one subscription is active. */
    Optional<ClientStateSnapshot> current();

    /**
     * Start receiving state. The listener is never called by this method; its first callback is a
     * point-in-time baseline, and later continuous changes are delivered at a safe update point.
     * If a session change or history gap invalidates the cached baseline before that callback,
     * the latest valid sample becomes the baseline and no transition is emitted across the gap.
     * Listener failures are isolated and cancel only that subscription.
     * @param listener consumer for immutable state batches
     * @return an idempotently cancellable subscription owned by the calling plugin instance
     */
    ClientStateSubscription subscribe(Consumer<ClientStateBatch> listener);
}
