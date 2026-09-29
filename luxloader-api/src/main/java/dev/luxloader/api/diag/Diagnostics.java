package dev.luxloader.api.diag;

/**
 * Structured diagnostic output that users can export to help reproduce rendering failures. Log format:
 * [LuxLoader][source] message, where source identifies a plugin, pipeline or pass.
 */
public interface Diagnostics {

    /** Informational message. */
    void info(String message);

    /** Warning that does not prevent operation. */
    void warn(String message);

    /** Error disabling part of a plugin or pipeline. */
    void error(String message);

    /** Error with an exception stack trace. */
    void error(String message, Throwable cause);

    /**
     * Debug output only in diagnostic mode. Implementations short-circuit when disabled to keep per-frame
     * calls inexpensive.
     */
    void debug(String message);

    /** Whether diagnostics.verbose is enabled. */
    boolean isVerbose();

    /**
     * Record a structured fact for the environment section. Facts capture current state: exports use the
     * latest value instead of repeating per-frame logs.
     */
    void fact(String key, String value);

    /** Increment a counter; exports include totals and recent values. */
    void counter(String key, long delta);

    /** Set an instantaneous metric, such as GPU frame time. */
    void metric(String key, double value, String unit);

    /** Measure and record a code region with nanosecond precision. */
    default <T> T measure(String key, java.util.function.Supplier<T> action) {
        long start = System.nanoTime();
        try {
            return action.get();
        } finally {
            metric(key, (System.nanoTime() - start) / 1_000_000.0, "ms");
        }
    }

    /** Current diagnostic source for consistent nested prefixes. */
    Diagnostics scoped(String scope);

    /**
     * Export environment, devices, capabilities, plugins, pipelines, configuration, facts and metrics as
     * text. Default reports go under config/luxloader/reports/ with timestamps and scene identifiers.
     */
    String exportReport();

    /** Export to a file and return its path. */
    java.nio.file.Path exportReportToFile();

    /** Diagnostic report file extension. */
    String REPORT_EXTENSION = ".txt";
}
