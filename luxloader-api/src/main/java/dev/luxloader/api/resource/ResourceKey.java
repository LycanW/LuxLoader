package dev.luxloader.api.resource;

import java.util.Objects;

/** A normalized resource-pack path. Resolves the highest-priority effective resource, not a pack file. */
public record ResourceKey(String namespace, String path) {
    public ResourceKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+")
                || path.startsWith("/") || path.endsWith("/") || path.contains("//")
                || java.util.Arrays.stream(path.split("/")).anyMatch(p -> p.equals(".") || p.equals(".."))) {
            throw new IllegalArgumentException("Invalid resource key: " + namespace + ":" + path);
        }
    }

    @Override public String toString() { return namespace + ":" + path; }
}
