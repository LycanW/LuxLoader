package dev.luxloader.core.diag;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.diag.Diagnostics;

import java.nio.charset.StandardCharsets;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Structured diagnostics: facts hold current state, counters accumulate events, metrics retain
 * instantaneous values and logs preserve readable execution history. Exports combine these sections
 * for reproducible environment comparisons.
 */
public final class DiagnosticsImpl implements Diagnostics {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Path reportDirectory;
    private final String scope;
    private final boolean verbose;
    private final List<String> logBuffer = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, String> facts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, String> metrics = new ConcurrentHashMap<>();
    private final Map<String, Double> durationPeaks = new ConcurrentHashMap<>();
    private final int maxLogLines;

    public DiagnosticsImpl(Path reportDirectory, String scope, boolean verbose) {
        this(reportDirectory, scope, verbose, 4000);
    }

    public DiagnosticsImpl(Path reportDirectory, String scope, boolean verbose, int maxLogLines) {
        this.reportDirectory = reportDirectory;
        this.scope = scope == null ? "" : scope;
        this.verbose = verbose;
        this.maxLogLines = maxLogLines;
    }

    @Override
    public void info(String message) {
        log("INFO", message);
    }

    @Override
    public void warn(String message) {
        log("WARN", message);
    }

    @Override
    public void error(String message) {
        log("ERROR", message);
    }

    @Override
    public void error(String message, Throwable cause) {
        log("ERROR", message + " —— " + describe(cause));
    }

    @Override
    public void debug(String message) {
        if (verbose) {
            log("DEBUG", message);
        }
    }

    @Override
    public boolean isVerbose() {
        return verbose;
    }

    @Override
    public void fact(String key, String value) {
        if (key != null && value != null) {
            facts.put(key, value);
        }
    }

    @Override
    public void counter(String key, long delta) {
        if (key != null) {
            counters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
        }
    }

    @Override
    public void metric(String key, double value, String unit) {
        if (key != null) {
            metrics.put(key, String.format(java.util.Locale.ROOT, "%.3f %s", value, unit == null ? "" : unit));
            if ("ms".equals(unit) && Double.isFinite(value) && value >= 0
                    && !key.endsWith(".peak") && !key.contains(".avg.")) {
                durationPeaks.merge(key, value, Math::max);
            }
        }
    }

    @Override
    public Diagnostics scoped(String newScope) {
        DiagnosticsImpl child = new DiagnosticsImpl(reportDirectory, newScope, verbose, maxLogLines);
        // Child scopes share storage so their records appear in the same report.
        child.facts.putAll(this.facts);
        child.counters.putAll(this.counters);
        child.metrics.putAll(this.metrics);
        child.logBuffer.addAll(this.logBuffer);
        return new ScopedView(this, newScope == null ? "" : newScope);
    }

    @Override
    public String exportReport() {
        StringBuilder sb = new StringBuilder(8192);
        sb.append(tr("LuxLoader Diagnostic Report")).append(System.lineSeparator());
        sb.append(tr("Generated: ")).append(LocalDateTime.now()).append(System.lineSeparator());
        sb.append(tr("Scope: ")).append(scope.isEmpty() ? "<root>" : scope).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        sb.append(tr("===== Peak durations (this run, including initialization) =====")).append(System.lineSeparator());
        durationPeaks.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("  ").append(e.getKey()).append(".peak = ")
                        .append(String.format(java.util.Locale.ROOT, "%.3f ms", e.getValue()))
                        .append(System.lineSeparator()));
        sb.append(System.lineSeparator());

        sb.append(tr("===== Environment facts =====")).append(System.lineSeparator());
        if (facts.isEmpty()) {
            sb.append(tr("  (none)")).append(System.lineSeparator());
        } else {
            facts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue())
                            .append(System.lineSeparator()));
        }
        sb.append(System.lineSeparator());

        sb.append(tr("===== Metrics =====")).append(System.lineSeparator());
        if (metrics.isEmpty()) {
            sb.append(tr("  (none)")).append(System.lineSeparator());
        } else {
            metrics.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue())
                            .append(System.lineSeparator()));
        }
        sb.append(System.lineSeparator());

        sb.append(tr("===== Counters =====")).append(System.lineSeparator());
        if (counters.isEmpty()) {
            sb.append(tr("  (none)")).append(System.lineSeparator());
        } else {
            counters.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("  ").append(e.getKey()).append(" = ")
                            .append(e.getValue().get()).append(System.lineSeparator()));
        }
        sb.append(System.lineSeparator());

        sb.append(tr("===== Additional sections =====")).append(System.lineSeparator());
        for (Map.Entry<String, Supplier<String>> e : sections.entrySet()) {
            sb.append("--- ").append(tr(e.getKey())).append(" ---").append(System.lineSeparator());
            try {
                sb.append(e.getValue().get()).append(System.lineSeparator());
            } catch (RuntimeException ex) {
                sb.append(tr("  <Generation failed: ")).append(describe(ex)).append(">").append(System.lineSeparator());
            }
        }
        sb.append(System.lineSeparator());

        sb.append(tr("===== Logs (last ")).append(logBuffer.size()).append(tr(" lines) ====="))
                .append(System.lineSeparator());
        for (String line : logBuffer) {
            sb.append(line).append(System.lineSeparator());
        }
        return sb.toString();
    }

    @Override
    public Path exportReportToFile() {
        try {
            Files.createDirectories(reportDirectory);
            String name = "luxloader-report-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + REPORT_EXTENSION;
            Path target = reportDirectory.resolve(name);
            Files.writeString(target, exportReport(), StandardCharsets.UTF_8);
            return target;
        } catch (Exception e) {
            throw new dev.luxloader.core.util.LuxException(tr("Failed to export diagnostic report: ") + describe(e), e);
        }
    }

    // Loader internal helpers.

    private final Map<String, Supplier<String>> sections = new LinkedHashMap<>();

    /**
     * Register a section generated at export time, such as devices, capabilities, pipelines or
     * configuration. The diagnostics implementation remains independent of those subsystems.
     */
    public void section(String title, Supplier<String> generator) {
        if (title != null && generator != null) {
            sections.put(title, generator);
        }
    }

    /** Buffered log line count. */
    public int logLineCount() {
        return logBuffer.size();
    }

    /** All facts for reuse in other diagnostic sections. */
    public Map<String, String> factsSnapshot() {
        return Map.copyOf(facts);
    }

    /** Append an already-formatted log line. */
    public void rawLine(String line) {
        push(line);
    }

    private void log(String level, String message) {
        String line = "[" + LocalDateTime.now().format(TS) + "][LuxLoader]"
                + (scope.isEmpty() ? "" : "[" + scope + "]")
                + "[" + level + "] " + message;
        push(line);
        mirrorToConsole(level, line);
    }

    /**
     * Forward WARN/ERROR and verbose DEBUG to process output so failures reach latest.log even without
     * report export. Use standard streams to keep core independent of a logging framework. Do not forward
     * per-frame INFO noise.
     */
    private void mirrorToConsole(String level, String line) {
        boolean important = "ERROR".equals(level) || "WARN".equals(level);
        if (!important && !(verbose && "DEBUG".equals(level))) {
            return;
        }
        // Use an explicit loader prefix to distinguish host output.
        if ("ERROR".equals(level) || "WARN".equals(level)) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
    }

    private void push(String line) {
        logBuffer.add(line);
        // Retain only the latest N lines to bound memory usage.
        while (logBuffer.size() > maxLogLines) {
            logBuffer.remove(0);
        }
    }

    /** Summarize an exception with its deepest cause and first application stack frame. */
    public static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && cause != t && depth++ < 5) {
            sb.append(" <- ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            cause = cause.getCause();
        }
        StackTraceElement[] stack = t.getStackTrace();
        if (stack.length > 0) {
            sb.append(" @ ").append(stack[0]);
        }
        return sb.toString();
    }

    /** Complete stack for a copyable pipeline failure report. */
    public static String stackTrace(Throwable cause) {
        if (cause == null) {
            return tr("Unknown cause");
        }
        StringWriter text = new StringWriter();
        cause.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    /** Child scope view forwarding to the parent storage so every scope exports into one report. */
    private static final class ScopedView implements Diagnostics {

        private final DiagnosticsImpl parent;
        private final String scope;

        ScopedView(DiagnosticsImpl parent, String scope) {
            this.parent = parent;
            this.scope = scope;
        }

        @Override
        public void info(String message) {
            parent.log("INFO", prefix(message));
        }

        @Override
        public void warn(String message) {
            parent.log("WARN", prefix(message));
        }

        @Override
        public void error(String message) {
            parent.log("ERROR", prefix(message));
        }

        @Override
        public void error(String message, Throwable cause) {
            parent.log("ERROR", prefix(message) + " —— " + describe(cause));
        }

        @Override
        public void debug(String message) {
            if (parent.isVerbose()) {
                parent.log("DEBUG", prefix(message));
            }
        }

        @Override
        public boolean isVerbose() {
            return parent.isVerbose();
        }

        @Override
        public void fact(String key, String value) {
            parent.fact(scope.isEmpty() ? key : scope + "." + key, value);
        }

        @Override
        public void counter(String key, long delta) {
            parent.counter(scope.isEmpty() ? key : scope + "." + key, delta);
        }

        @Override
        public void metric(String key, double value, String unit) {
            parent.metric(scope.isEmpty() ? key : scope + "." + key, value, unit);
        }

        @Override
        public Diagnostics scoped(String newScope) {
            return new ScopedView(parent, scope.isEmpty() ? newScope : scope + "/" + newScope);
        }

        @Override
        public String exportReport() {
            return parent.exportReport();
        }

        @Override
        public Path exportReportToFile() {
            return parent.exportReportToFile();
        }

        private String prefix(String message) {
            return scope.isEmpty() ? message : "[" + scope + "] " + message;
        }
    }

    /** Format capabilities as a diagnostic section. */
    public static String formatCapabilities(List<CapabilityDescriptor> capabilities) {
        StringBuilder sb = new StringBuilder();
        for (CapabilityDescriptor d : capabilities) {
            sb.append("  ").append(d.describe()).append(System.lineSeparator());
        }
        return sb.toString();
    }

    /** Collect recorded log lines for test assertions. */
    public List<String> logLines() {
        return List.copyOf(new ArrayList<>(logBuffer));
    }
}
