package dev.luxloader.api.event;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable namespaced identity for a plugin-defined event type. */
public record EventTypeId(String namespace, String path) {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9_.-]+");

    public EventTypeId {
        namespace = Objects.requireNonNull(namespace, "namespace");
        path = Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches() || path.isBlank()) {
            throw new IllegalArgumentException("Invalid event type ID: " + namespace + ":" + path);
        }
        for (String segment : path.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid event type ID: " + namespace + ":" + path);
            }
        }
    }

    public static EventTypeId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':') || separator == value.length() - 1) {
            throw new IllegalArgumentException("Event type ID must use namespace:path form: " + value);
        }
        return new EventTypeId(value.substring(0, separator), value.substring(separator + 1));
    }

    /** A plugin may register its own ID or a slash-delimited child ID. */
    public boolean isOwnedBy(EventTypeId owner) {
        Objects.requireNonNull(owner, "owner");
        return namespace.equals(owner.namespace)
                && (path.equals(owner.path) || path.startsWith(owner.path + "/"));
    }

    @Override public String toString() { return namespace + ":" + path; }
}
