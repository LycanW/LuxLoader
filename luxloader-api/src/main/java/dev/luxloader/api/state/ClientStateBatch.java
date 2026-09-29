package dev.luxloader.api.state;

import java.util.List;
import java.util.Objects;

/** One immutable delivery at a client safe update point; an initial batch establishes a baseline. */
public record ClientStateBatch(
        long firstSequence,
        long lastSequence,
        ClientStateSnapshot snapshot,
        List<SprintTransition> sprintTransitions,
        boolean initial,
        ResyncReason resyncReason,
        long droppedSamples) {

    public ClientStateBatch {
        if (firstSequence < 0 || lastSequence < firstSequence || droppedSamples < 0) {
            throw new IllegalArgumentException("invalid batch sequence or dropped-sample count");
        }
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        sprintTransitions = sprintTransitions == null ? List.of() : List.copyOf(sprintTransitions);
        resyncReason = resyncReason == null ? ResyncReason.NONE : resyncReason;
        if (initial && resyncReason != ResyncReason.NONE) {
            throw new IllegalArgumentException("an initial batch cannot also be a resynchronization");
        }
        if (resyncReason != ResyncReason.NONE && !sprintTransitions.isEmpty()) {
            throw new IllegalArgumentException("a resynchronization batch cannot contain gap-spanning transitions");
        }
    }

    /**
     * A state-derived transition. It reports a client-observed difference and is not a request,
     * action callback, or server-confirmed sprint command.
     */
    public record SprintTransition(
            long sequence,
            ClientStateSnapshot.EntityIdentity player,
            boolean wasSprinting,
            boolean isSprinting,
            Source source) {
        public SprintTransition {
            if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
            player = Objects.requireNonNull(player, "player");
            source = source == null ? Source.CLIENT_OBSERVED_STATE_DIFFERENCE : source;
            if (wasSprinting == isSprinting) throw new IllegalArgumentException("transition must change sprint state");
        }

        public enum Source { CLIENT_OBSERVED_STATE_DIFFERENCE }
    }

    /** Indicates an explicit history gap; consumers should replace cached state from snapshot. */
    public enum ResyncReason { NONE, QUEUE_OVERFLOW, WORLD_SESSION_CHANGED, SAMPLE_CAPTURE_FAILED }
}
