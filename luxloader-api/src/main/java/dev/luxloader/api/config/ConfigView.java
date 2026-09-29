package dev.luxloader.api.config;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only plugin configuration section in config/luxloader/luxloader.json. Each plugin owns a
 * section keyed by its identifier; paths use dots. Missing or mistyped values return fallbacks instead
 * of throwing; the host reports ignored keys.
 */
public interface ConfigView {

    /** Read a boolean. */
    boolean getBoolean(String path, boolean fallback);

    /** Read an integer. */
    int getInt(String path, int fallback);

    /** Read a long integer. */
    long getLong(String path, long fallback);

    /** Read a floating-point value. */
    float getFloat(String path, float fallback);

    /** Read a floating-point value clamped to [min, max]. */
    default float getFloat(String path, float fallback, float min, float max) {
        float v = getFloat(path, fallback);
        return v < min ? min : (v > max ? max : v);
    }

    /** Read a string. */
    String getString(String path, String fallback);

    /** Parse an enum from a string key. On failure, return fallback and record the ignored value. */
    <E extends Enum<E>> E getEnum(String path, Class<E> type, E fallback);

    /** Read a string list. */
    List<String> getStringList(String path, List<String> fallback);

    /** Whether explicitly set, distinguishing user values from defaults. */
    boolean has(String path);

    /** Read a raw String, Number, Boolean, List or Map. */
    Optional<Object> raw(String path);

    /** Direct child keys of this section. */
    List<String> childKeys();

    /** Write during configuration load/save only; runtime writes throw UnsupportedOperationException. */
    default void set(String path, Object value) {
        throw new UnsupportedOperationException(tr("This configuration view is read-only"));
    }

    /** Remove a key. */
    default boolean remove(String path) {
        throw new UnsupportedOperationException(tr("This configuration view is read-only"));
    }

    /**
     * Validate a nonempty path without consecutive separators.
     * @throws IllegalArgumentException for invalid paths
     */
    static String requireValidPath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException(tr("Configuration path must not be empty"));
        }
        if (path.contains("..") || path.startsWith(".") || path.endsWith(".")) {
            throw new IllegalArgumentException(tr("Invalid configuration path: ") + path);
        }
        return path;
    }
}
