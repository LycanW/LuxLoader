package dev.luxloader.api.event;

import dev.luxloader.api.state.ClientStateBatch;
import dev.luxloader.api.state.ClientStateSnapshot;

import java.util.List;
import java.util.Objects;

/** One bounded immutable delivery at the client safe update point. */
public record ClientEventBatch(
        long firstSequence,
        long lastSequence,
        ClientStateSnapshot snapshot,
        List<ClientEvent> events,
        boolean initial,
        ClientStateBatch.ResyncReason resyncReason,
        long droppedEvents) {

    public ClientEventBatch {
        if (firstSequence < 0 || lastSequence < firstSequence || droppedEvents < 0) {
            throw new IllegalArgumentException("invalid event batch sequence or dropped count");
        }
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        events = events == null ? List.of() : List.copyOf(events);
        resyncReason = resyncReason == null ? ClientStateBatch.ResyncReason.NONE : resyncReason;
        if (initial && resyncReason != ClientStateBatch.ResyncReason.NONE) {
            throw new IllegalArgumentException("an initial batch cannot also be a resynchronization");
        }
        long generation = snapshot.world().generation();
        for (ClientEvent event : events) {
            if (event.stamp().sessionGeneration() != generation) {
                throw new IllegalArgumentException("event batches cannot mix world sessions");
            }
        }
    }
}
