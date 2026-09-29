package dev.luxloader.api.resource;

import java.util.Objects;

/** Immutable resource-stack generation and availability. Failures never make an old generation current. */
public record ResourceState(long generation, Phase phase) {
    public enum Phase { READY, RELOADING, FAILED, CANCELLED, CLOSED }
    public ResourceState { Objects.requireNonNull(phase, "phase"); }
    public boolean ready() { return phase == Phase.READY; }
}
