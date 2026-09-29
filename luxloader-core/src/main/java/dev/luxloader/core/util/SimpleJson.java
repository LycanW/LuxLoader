package dev.luxloader.core.util;

import static dev.luxloader.api.i18n.Messages.tr;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free JSON to avoid mod-classpath library conflicts. Maps objects to ordered maps, arrays
 * to lists, numbers to Long/Double, booleans and null directly. Rejects comments, trailing commas,
 * single quotes and nonfinite values; generated _comment fields provide standard-JSON annotations.
 */
public final class SimpleJson {

    private SimpleJson() {
    }

    // Parsing.

    /**
     * Parses JSON.
     * @param text input
     * @return Map/List/String/Number/Boolean/null
     * @throws JsonException syntax error with line and column
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException(tr("Input is null"));
        }
        try (Reader r = new StringReader(text)) {
            Parser p = new Parser(r);
            Object value = p.parseValue();
            p.skipWhitespace();
            if (!p.atEnd()) {
                throw new JsonException(tr("Line ") + p.line + tr(", column ") + p.column + tr(": trailing content after document end"));
            }
            return value;
        } catch (IOException e) {
            throw new JsonException(tr("Read failed: ") + e.getMessage(), e);
        }
    }

    /** Parses an object; returns an empty map plus an error for non-object roots. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new JsonException(tr("Root element must be an object, got ") + typeName(o));
    }

    /** Reads a file; missing files yield an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object parsed = parse(text);
        if (parsed instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new JsonException(tr("Configuration root must be an object: ") + path);
    }

    // Serialization.

    /** Generates compact JSON. */
    public static String write(Object value) {
        StringWriter sw = new StringWriter();
        try {
            write(value, sw, -1, 0);
        } catch (IOException e) {
            throw new JsonException(tr("Write failed: ") + e.getMessage(), e);
        }
        return sw.toString();
    }

    /**
     * Generates indented JSON.
     * @param indent spaces per level, typically 2
     */
    public static String writePretty(Object value, int indent) {
        StringWriter sw = new StringWriter();
        try {
            write(value, sw, indent, 0);
        } catch (IOException e) {
            throw new JsonException(tr("Write failed: ") + e.getMessage(), e);
        }
        return sw.toString();
    }

    /** Writes through a temporary file and atomic replacement, retaining a .bak backup to protect configuration. */
    public static void writeFileAtomic(Path path, Object value, int indent) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, writePretty(value, indent), StandardCharsets.UTF_8);
        if (Files.exists(path)) {
            Path backup = path.resolveSibling(path.getFileName() + ".bak");
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void write(Object value, Writer w, int indent, int depth) throws IOException {
        if (value == null) {
            w.write("null");
        } else if (value instanceof String s) {
            writeString(s, w);
        } else if (value instanceof Boolean b) {
            w.write(b ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                w.write("0");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                w.write(Long.toString((long) d));
            } else {
                w.write(trimTrailingZeros(Double.toString(d)));
            }
        } else if (value instanceof Number n) {
            w.write(n.toString());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, w, indent, depth);
        } else if (value instanceof Iterable<?> it) {
            writeArray(it, w, indent, depth);
        } else if (value instanceof Object[] arr) {
            writeArray(List.of(arr), w, indent, depth);
        } else {
            // Serialize unknown types as strings instead of failing the save.
            writeString(String.valueOf(value), w);
        }
    }

    private static void writeObject(Map<?, ?> map, Writer w, int indent, int depth) throws IOException {
        if (map.isEmpty()) {
            w.write("{}");
            return;
        }
        w.write('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                w.write(',');
            }
            first = false;
            newlineIndent(w, indent, depth + 1);
            writeString(String.valueOf(e.getKey()), w);
            w.write(':');
            if (indent >= 0) {
                w.write(' ');
            }
            write(e.getValue(), w, indent, depth + 1);
        }
        newlineIndent(w, indent, depth);
        w.write('}');
    }

    private static void writeArray(Iterable<?> it, Writer w, int indent, int depth) throws IOException {
        java.util.Iterator<?> iter = it.iterator();
        if (!iter.hasNext()) {
            w.write("[]");
            return;
        }
        w.write('[');
        boolean first = true;
        while (iter.hasNext()) {
            if (!first) {
                w.write(',');
            }
            first = false;
            newlineIndent(w, indent, depth + 1);
            write(iter.next(), w, indent, depth + 1);
        }
        newlineIndent(w, indent, depth);
        w.write(']');
    }

    private static void newlineIndent(Writer w, int indent, int depth) throws IOException {
        if (indent < 0) {
            return;
        }
        w.write('\n');
        w.write(" ".repeat(indent * depth));
    }

    private static void writeString(String s, Writer w) throws IOException {
        w.write('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> w.write("\\\"");
                case '\\' -> w.write("\\\\");
                case '\n' -> w.write("\\n");
                case '\r' -> w.write("\\r");
                case '\t' -> w.write("\\t");
                case '\b' -> w.write("\\b");
                case '\f' -> w.write("\\f");
                default -> {
                    if (c < 0x20) {
                        w.write(String.format("\\u%04x", (int) c));
                    } else {
                        w.write(c);
                    }
                }
            }
        }
        w.write('"');
    }

    private static String trimTrailingZeros(String s) {
        if (!s.contains(".")) {
            return s;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    /** Type name for diagnostics. */
    public static String typeName(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof Map<?, ?>) {
            return "object";
        }
        if (o instanceof List<?>) {
            return "array";
        }
        if (o instanceof String) {
            return "string";
        }
        if (o instanceof Boolean) {
            return "boolean";
        }
        if (o instanceof Number) {
            return "number";
        }
        return o.getClass().getSimpleName();
    }

    /** Recursively merges overlay keys into base. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            Object existing = out.get(e.getKey());
            Object incoming = e.getValue();
            if (existing instanceof Map<?, ?> em && incoming instanceof Map<?, ?> im) {
                out.put(e.getKey(), deepMerge((Map<String, Object>) em, (Map<String, Object>) im));
            } else {
                out.put(e.getKey(), incoming);
            }
        }
        return out;
    }

    /** JSON syntax error. */
    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }

        public JsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Recursive-descent parser. */
    private static final class Parser {
        private final Reader reader;
        private int pushback = -2;
        private int line = 1;
        private int column = 0;

        Parser(Reader reader) {
            this.reader = reader;
        }

        boolean atEnd() {
            return peek() == -1;
        }

        private int read() {
            int c;
            if (pushback != -2) {
                c = pushback;
                pushback = -2;
            } else {
                try {
                    c = reader.read();
                } catch (IOException e) {
                    throw new JsonException(tr("Read failed: ") + e.getMessage(), e);
                }
            }
            if (c == '\n') {
                line++;
                column = 0;
            } else if (c >= 0) {
                column++;
            }
            return c;
        }

        private int peek() {
            if (pushback == -2) {
                pushback = read();
            }
            return pushback;
        }

        private void skipWhitespace() {
            while (true) {
                int c = peek();
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    read();
                } else {
                    return;
                }
            }
        }

        private JsonException error(String message) {
            return new JsonException(tr("Line ") + line + tr(", column ") + column + tr("SimpleJson.a242affa55", ": ") + message);
        }

        Object parseValue() {
            skipWhitespace();
            int c = peek();
            return switch (c) {
                case '{' -> parseObjectInternal();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                case -1 -> throw error(tr("Unexpected end of input"));
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw error(tr("Invalid character '") + (char) c + "'");
                }
            };
        }

        private Map<String, Object> parseObjectInternal() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                read();
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error(tr("Object keys must be strings"));
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                int c = read();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    return map;
                }
                throw error(tr("Expected ',' or '}' in object"));
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                read();
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                int c = read();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    return list;
                }
                throw error(tr("Expected ',' or ']' in array"));
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                int c = read();
                if (c == -1) {
                    throw error(tr("Unterminated string"));
                }
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    int esc = read();
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            StringBuilder hex = new StringBuilder();
                            for (int i = 0; i < 4; i++) {
                                int h = read();
                                if (h == -1) {
                                    throw error(tr("Incomplete \\u escape"));
                                }
                                hex.append((char) h);
                            }
                            try {
                                sb.append((char) Integer.parseInt(hex.toString(), 16));
                            } catch (NumberFormatException e) {
                                throw error(tr("Invalid \\u escape: ") + hex);
                            }
                        }
                        default -> throw error(tr("Invalid escape character '\\") + (char) esc + "'");
                    }
                } else {
                    sb.append((char) c);
                }
            }
        }

        private Boolean parseBoolean() {
            int c = read();
            if (c == 't') {
                expectWord("rue");
                return Boolean.TRUE;
            }
            expectWord("alse");
            return Boolean.FALSE;
        }

        private Object parseNull() {
            read();
            expectWord("ull");
            return null;
        }

        private void expectWord(String rest) {
            for (int i = 0; i < rest.length(); i++) {
                int c = read();
                if (c != rest.charAt(i)) {
                    throw error(tr("Invalid literal"));
                }
            }
        }

        private Number parseNumber() {
            StringBuilder sb = new StringBuilder();
            int c = peek();
            if (c == '-') {
                sb.append((char) read());
            }
            boolean floating = false;
            while (true) {
                c = peek();
                if (c >= '0' && c <= '9') {
                    sb.append((char) read());
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = floating || c == '.' || c == 'e' || c == 'E';
                    sb.append((char) read());
                } else {
                    break;
                }
            }
            String s = sb.toString();
            try {
                if (floating) {
                    return Double.parseDouble(s);
                }
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException e) {
                    return Double.parseDouble(s);
                }
            } catch (NumberFormatException e) {
                throw error(tr("Invalid number: ") + s);
            }
        }

        private void expect(char expected) {
            int c = read();
            if (c != expected) {
                throw error(tr("Expected '") + expected + tr("', got ") + (c == -1 ? tr("end of document") : "'" + (char) c + "'"));
            }
        }
    }
}
