package dev.luxloader.api.event;

import dev.luxloader.api.state.ClientStateSnapshot;

/** Immutable event delivered at a client safe update point. */
public sealed interface ClientEvent permits BehaviorEvent, CustomEvent {
    EventStamp stamp();

    /** Event order, the active world-session generation, and the nearest captured logical-time boundary. */
    record EventStamp(long sequence, long sessionGeneration, ClientStateSnapshot.LogicalTime logicalTime) {
        public EventStamp {
            if (sequence < 1 || sessionGeneration < 0) {
                throw new IllegalArgumentException("invalid event sequence or session generation");
            }
            if (logicalTime == null) throw new NullPointerException("logicalTime");
        }
    }
}
