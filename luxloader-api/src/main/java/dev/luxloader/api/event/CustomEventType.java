package dev.luxloader.api.event;

import java.util.Objects;

/** Public descriptor for one version of a custom event type. Ownership is enforced by the host service. */
public record CustomEventType(EventTypeId id, int version) {
    public CustomEventType {
        id = Objects.requireNonNull(id, "id");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
    }
}
