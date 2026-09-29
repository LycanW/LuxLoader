package dev.luxloader.api.event;

import java.util.Objects;

/** One immutable plugin-published value for a registered namespaced custom event type. */
public record CustomEvent(EventStamp stamp, CustomEventType type,
                          EventValue.ObjectValue payload) implements ClientEvent {
    public CustomEvent {
        stamp = Objects.requireNonNull(stamp, "stamp");
        type = Objects.requireNonNull(type, "type");
        payload = Objects.requireNonNull(payload, "payload");
    }
}
