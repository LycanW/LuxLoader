package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured compiler diagnostic parsed from headers, locations, source echoes, carets and optional
 * ANSI formatting. Supports both location-rich errors and standalone downstream-library failures.
 * @param severity severity
 * @param code optional compiler code such as E30015
 * @param message main message
 * @param file filename, empty when unknown
 * @param line line number, zero when unknown
 * @param column column, zero when unknown
 * @param detail caret explanation
 * @param context echoed source lines
 */
public record ShaderDiagnostic(
        Severity severity,
        String code,
        String message,
        String file,
        int line,
        int column,
        String detail,
        List<ContextLine> context) {

    /** Severity. */
    public enum Severity {
        /** Error: compilation fails. */
        ERROR,
        /** Warning: compilation continues. */
        WARNING,
        /** Additional compiler note. */
        NOTE,
        /** General information. */
        INFO;

        static Severity parse(String raw) {
            if (raw == null) {
                return INFO;
            }
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "error", "fatal", "err" -> ERROR;
                case "warning", "warn" -> WARNING;
                case "note" -> NOTE;
                default -> INFO;
            };
        }

        /** Localized label for UI and logs. */
        public String displayName() {
            return switch (this) {
                case ERROR -> tr("Error");
                case WARNING -> tr("Warning");
                case NOTE -> tr("Note");
                case INFO -> tr("Info");
            };
        }


        /** Compatibility alias; the label now follows the selected language. */
        @Deprecated
        public String chinese() { return displayName(); }
    }

    /**
     * Echoed source line.
     * @param lineNumber source line number
     * @param text original text
     * @param primary whether this is the failing line
     */
    public record ContextLine(int lineNumber, String text, boolean primary) {
    }

    public ShaderDiagnostic {
        Objects.requireNonNull(severity, "severity");
        code = code == null ? "" : code.trim();
        message = message == null ? "" : message.trim();
        file = file == null ? "" : file;
        detail = detail == null ? "" : detail.trim();
        context = context == null ? List.of() : List.copyOf(context);
        if (line < 0) {
            line = 0;
        }
        if (column < 0) {
            column = 0;
        }
    }

    /** Whether this is an error. */
    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** Whether a source location is available. */
    public boolean hasLocation() {
        return !file.isEmpty() && line > 0;
    }

    /** Formatted location, e.g. scene.slang:42:7. */
    public String location() {
        if (file.isEmpty()) {
            return "";
        }
        if (line <= 0) {
            return file;
        }
        return column > 0 ? file + ":" + line + ":" + column : file + ":" + line;
    }

    /** Replaces a temporary path with the source's logical filename. */
    public ShaderDiagnostic withFile(String newFile) {
        return new ShaderDiagnostic(severity, code, message, newFile, line, column, detail, context);
    }

    /**
     * Localized guidance for common compiler/environment errors, supplementing the compiler's raw message.
     * @return advice, or empty when no additional guidance applies
     */
    public String hint() {
        String c = code.toUpperCase(Locale.ROOT);
        String m = message.toLowerCase(Locale.ROOT);
        if (c.equals("E00100") || m.contains("failed to load downstream compiler")) {
            return tr("Missing compiler component: SPIR-V generation requires downstream spirv-opt. ")
                    + tr("When bundling a compiler, extract slang-glslang alongside slangc; ")
                    + tr("even Slang input fails without it.");
        }
        if (c.equals("E99996") || m.contains("failed to load dynamic library")) {
            return tr("Dynamic library loading failed: compiler dependencies may be missing ")
                    + tr("or their versions may not match.");
        }
        if (c.equals("E00001") || m.contains("cannot open file")) {
            return tr("Source file not found. Check case and location; ")
                    + tr("read classpath resource contents before passing them to the compiler.");
        }
        if (c.equals("E30015") || m.contains("undefined identifier")) {
            return tr("Undefined name. Check spelling and includeDirs for external declarations; ")
                    + tr("pass required conditional-compilation macros through defines.");
        }
        if (c.equals("E15900") || m.contains("preprocessor error")) {
            return tr("Preprocessing failed: check #error directives, missing #include files and incompatible macro definitions.");
        }
        if (m.contains("profile implicitly upgraded") || c.equals("E41012")) {
            return tr("The compiler raised the target profile to support required capabilities. Usually harmless; ")
                    + tr("on older devices consider a lower SPIR-V target to avoid driver rejection.");
        }
        if (m.contains("spir-v version too old") || c.equals("E50011")) {
            return tr("The SPIR-V target is too old for required shader capabilities. ")
                    + tr("Raise the target profile, for example to spirv_1_5.");
        }
        if (c.startsWith("E39") || m.contains("expected") || m.contains("syntax")) {
            return tr("Syntax error: inspect the caret location for missing semicolons, unbalanced brackets or type mismatches.");
        }
        if (severity == Severity.WARNING) {
            return tr("This warning does not prevent compilation but may indicate behavior differences across devices.");
        }
        return "";
    }

    /** Multiline diagnostic text. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity.displayName());
        if (!code.isEmpty()) {
            sb.append('[').append(code).append(']');
        }
        if (!file.isEmpty()) {
            sb.append(' ').append(location());
        }
        sb.append(": ").append(message);
        for (ContextLine c : context) {
            sb.append(System.lineSeparator())
                    .append(c.primary() ? "  > " : "    ")
                    .append(c.lineNumber())
                    .append(" | ")
                    .append(c.text());
        }
        if (!detail.isEmpty()) {
            sb.append(System.lineSeparator()).append(tr("    Note: ")).append(detail);
        }
        String h = hint();
        if (!h.isEmpty()) {
            sb.append(System.lineSeparator()).append(tr("    Suggestion: ")).append(h);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return render();
    }

    // Parsing.

    /** Header, e.g. error[E30015]: undefined identifier. */
    private static final Pattern HEADER = Pattern.compile(
            "^\\s*(?<sev>error|warning|note|info|fatal)\\s*(?:\\[(?<code>[A-Za-z]?\\d+)\\])?\\s*:\\s*(?<msg>.*)$",
            Pattern.CASE_INSENSITIVE);

    /** Location line, e.g. --> bad.comp:8:31. */
    private static final Pattern LOCATION = Pattern.compile("^\\s*-->\\s*(?<loc>.+?)\\s*$");

    /** file:line:col or file:line; greedy filename matching absorbs Windows drive colons. */
    private static final Pattern LOCATION_BODY = Pattern.compile(
            "^(?<file>.+?):(?<line>\\d+)(?::(?<col>\\d+))?$");

    /** Source echo line, e.g. 8 followed by source text. */
    private static final Pattern SOURCE_LINE = Pattern.compile("^\\s*(?<ln>\\d+)\\s*\\|\\s?(?<text>.*)$");

    /** Caret underline and optional explanatory text. */
    private static final Pattern CARET_LINE = Pattern.compile("^\\s*\\|\\s*(?<carets>\\^+)\\s*(?<tail>.*)$");

    /** A bare vertical bar is formatting whitespace without content. */
    private static final Pattern PIPE_ONLY = Pattern.compile("^\\s*\\|\\s*$");

    /** Additional note line. */
    private static final Pattern AUXILIARY = Pattern.compile("^\\s*=\\s*(?<text>.*)$");

    /** Strip compiler ANSI terminal colors from logs. */
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");

    /**
     * Parses compiler output.
     * @param output stderr, possibly mixed with stdout
     * @param fileRewriter optional temporary-to-logical filename mapping
     * @return diagnostics, or one informational entry carrying raw text if parsing finds no structure
     */
    public static List<ShaderDiagnostic> parseAll(String output, UnaryOperator<String> fileRewriter) {
        List<ShaderDiagnostic> result = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return result;
        }
        String clean = ANSI.matcher(output).replaceAll("");
        String[] lines = clean.split("\\R", -1);

        Mutable current = null;
        List<String> unresolved = new ArrayList<>();

        for (String raw : lines) {
            String line = raw;

            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                if (current != null) {
                    result.add(current.build());
                }
                current = new Mutable(Severity.parse(header.group("sev")),
                        header.group("code"), header.group("msg"));
                continue;
            }

            if (current == null) {
                if (!line.isBlank() && !line.equals("--'")) {
                    unresolved.add(line);
                }
                continue;
            }

            Matcher loc = LOCATION.matcher(line);
            if (loc.matches()) {
                applyLocation(current, loc.group("loc"));
                continue;
            }

            Matcher caret = CARET_LINE.matcher(line);
            if (caret.matches()) {
                applyCaret(current, caret);
                continue;
            }

            if (PIPE_ONLY.matcher(line).matches()) {
                // Ignore empty formatting bars.
                continue;
            }

            Matcher src = SOURCE_LINE.matcher(line);
            if (src.matches()) {
                int ln = Integer.parseInt(src.group("ln"));
                current.context.add(new ContextLine(ln, src.group("text"), ln == current.line));
                continue;
            }

            Matcher auxiliary = AUXILIARY.matcher(line);
            if (auxiliary.matches()) {
                current.appendDetail(auxiliary.group("text").strip());
                continue;
            }

            if (line.equals("--'")) {
                // This separator also terminates a child diagnostic note.
                if (line.endsWith("'") && !current.detail.isEmpty()) {
                    current.detailDone = true;
                }
                continue;
            }

            if (line.isBlank()) {
                continue;
            }

            // Accept textual continuation lines but discard symbol-only formatting so separators do not pollute messages.
            String trimmed = line.strip();
            if (!containsWordCharacter(trimmed)) {
                continue;
            }
            if (current.detail.isEmpty() && !current.context.isEmpty() && !current.detailDone) {
                current.appendDetail(trimmed);
            } else {
                current.message = current.message.isEmpty() ? trimmed : current.message + " " + trimmed;
            }
        }
        if (current != null) {
            result.add(current.build());
        }

        if (result.isEmpty() && !unresolved.isEmpty()) {
            result.add(new ShaderDiagnostic(Severity.INFO, "", String.join(System.lineSeparator(), unresolved),
                    "", 0, 0, "", List.of()));
        }

        if (fileRewriter != null) {
            List<ShaderDiagnostic> rewritten = new ArrayList<>(result.size());
            for (ShaderDiagnostic d : result) {
                rewritten.add(d.file().isEmpty() ? d : d.withFile(fileRewriter.apply(d.file())));
            }
            return List.copyOf(rewritten);
        }
        return List.copyOf(result);
    }

    private static boolean containsWordCharacter(String text) {
        return text.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private static void applyLocation(Mutable current, String loc) {
        Matcher body = LOCATION_BODY.matcher(loc.strip());
        if (!body.matches()) {
            // Location without line/column, such as a built-in source.
            if (current.file.isEmpty()) {
                current.file = loc.strip();
            }
            return;
        }
        current.file = body.group("file");
        try {
            current.line = Integer.parseInt(body.group("line"));
            current.column = body.group("col") == null ? 0 : Integer.parseInt(body.group("col"));
        } catch (NumberFormatException ignored) {
            // Keep the file but discard out-of-range line numbers.
        }
    }

    private static void applyCaret(Mutable current, Matcher caret) {
        String tail = caret.group("tail").strip();
        // Zero-based start column of the caret underline.
        int caretIndex = caret.group(0).indexOf('^');
        int pipeIndex = caret.group(0).indexOf('|');
        if (caretIndex > pipeIndex && current.column == 0) {
            current.column = Math.max(1, caretIndex - pipeIndex);
        }
        if (!tail.isEmpty()) {
            current.appendDetail(tail);
        }
    }

    /** Formats diagnostics with errors first, then by file and source position for readable repair order. */
    public static String render(List<ShaderDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "";
        }
        List<ShaderDiagnostic> sorted = new ArrayList<>(diagnostics);
        sorted.sort((a, b) -> {
            int bySeverity = a.severity().ordinal() - b.severity().ordinal();
            if (bySeverity != 0) {
                return bySeverity;
            }
            int byFile = a.file().compareTo(b.file());
            if (byFile != 0) {
                return byFile;
            }
            int byLine = Integer.compare(a.line(), b.line());
            if (byLine != 0) {
                return byLine;
            }
            return Integer.compare(a.column(), b.column());
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(System.lineSeparator());
            }
            sb.append(sorted.get(i).render());
        }
        return sb.toString();
    }

    /** One-line summary for diagnostic facts. */
    public static String summarize(List<ShaderDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return tr("No diagnostics");
        }
        Map<Severity, Integer> counts = new LinkedHashMap<>();
        for (ShaderDiagnostic d : diagnostics) {
            counts.merge(d.severity(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Severity s : Severity.values()) {
            Integer n = counts.get(s);
            if (n != null) {
                if (sb.length() > 0) {
                    sb.append('、');
                }
                sb.append(n).append(tr("ShaderDiagnostic.6fb2840d56", " entries")).append(s.displayName());
            }
        }
        return sb.toString();
    }

    /** Mutable parsing state while record components are assembled. */
    private static final class Mutable {
        final Severity severity;
        final String code;
        String message;
        String file = "";
        int line;
        int column;
        String detail = "";
        boolean detailDone;
        final List<ContextLine> context = new ArrayList<>();

        Mutable(Severity severity, String code, String message) {
            this.severity = severity;
            this.code = code;
            this.message = message == null ? "" : message.strip();
        }

        /** Appends notes separated by spaces for a readable single line. */
        void appendDetail(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            detail = detail.isEmpty() ? text : detail + " " + text;
        }

        ShaderDiagnostic build() {
            List<ContextLine> ctx = new ArrayList<>(context.size());
            for (ContextLine c : context) {
                ctx.add(new ContextLine(c.lineNumber(), c.text(), c.lineNumber() == line));
            }
            return new ShaderDiagnostic(severity, code, message, file, line, column, detail, ctx);
        }
    }
}
