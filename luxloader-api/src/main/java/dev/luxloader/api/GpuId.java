package dev.luxloader.api;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * Globally unique identifier for registered resources such as pipelines and backends. Use a
 * reverse-DNS namespace, for example dev.luxloader.upscaler.dlss5. Equality uses the full identifier;
 * ordering compares namespace and path lexically so conflict resolution remains deterministic across
 * machines.
 * @param namespace nonempty namespace, usually a reversed author domain
 * @param path nonempty path within the namespace
 */
public record GpuId(String namespace, String path) implements Comparable<GpuId> {

    public GpuId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.isBlank() || path.isBlank()) {
            throw new IllegalArgumentException(tr("GpuId namespace and path must not be empty: ") + namespace + ":" + path);
        }
        if (namespace.indexOf(':') >= 0) {
            throw new IllegalArgumentException(tr("namespace must not contain ':': ") + namespace);
        }
    }

    /** Parse a {@code namespace:path} identifier. */
    public static GpuId parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        int i = raw.indexOf(':');
        if (i <= 0 || i == raw.length() - 1) {
            throw new IllegalArgumentException(tr("Invalid GpuId; expected 'namespace:path': ") + raw);
        }
        return new GpuId(raw.substring(0, i), raw.substring(i + 1));
    }

    /** Parse tolerantly; return null for invalid user configuration. */
    public static GpuId tryParse(String raw) {
        try {
            return parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Derive a child in the same namespace, e.g. {@code GpuId.parse("a:b").child("c")} yields a:b/c. */
    public GpuId child(String childPath) {
        Objects.requireNonNull(childPath, "childPath");
        if (childPath.isBlank()) {
            throw new IllegalArgumentException(tr("childPath must not be empty"));
        }
        return new GpuId(namespace, path + "/" + childPath);
    }

    @Override
    public int compareTo(GpuId o) {
        int c = namespace.compareTo(o.namespace);
        return c != 0 ? c : path.compareTo(o.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
