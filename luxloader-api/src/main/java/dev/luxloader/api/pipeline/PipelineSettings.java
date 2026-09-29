package dev.luxloader.api.pipeline;

import dev.luxloader.api.config.ConfigView;

/**
 * Pipeline settings view. The loader generates files, validates types and builds controls from keys
 * declared with HostServices. Reads never throw for missing or mistyped values: they return the
 * supplied fallback, and ignored keys are reported in diagnostics.
 */
public interface PipelineSettings extends ConfigView {

    /** Setting source for diagnostics, such as defaults, file or UI. */
    String source();

    /**
     * Read a hot-reloadable enum.
     * @param path relative path
     * @param type enum class
     * @param fallback value when parsing fails
     */
    <E extends Enum<E>> E getEnumOrDefault(String path, Class<E> type, E fallback);

    /** Read and negate a boolean setting. */
    default boolean getBooleanNot(String path, boolean fallback) {
        return !getBoolean(path, fallback);
    }

    /**
     * Reads a bounded floating-point value.
     * @param path key path
     * @param fallback default value
     * @param min lower bound
     * @param max upper bound
     */
    default float getClamped(String path, float fallback, float min, float max) {
        float v = getFloat(path, fallback);
        if (Float.isNaN(v)) {
            return fallback;
        }
        return v < min ? min : (v > max ? max : v);
    }

    /** Read an integer constrained to a range. */
    default int getClamped(String path, int fallback, int min, int max) {
        int v = getInt(path, fallback);
        return v < min ? min : (v > max ? max : v);
    }

    /** Settings revision incremented on reload; pipelines use it to decide when to reread values. */
    long revision();

    /** Open host-provided configuration UI; return false in headless environments. */
    default boolean openEditor() {
        return false;
    }
}
