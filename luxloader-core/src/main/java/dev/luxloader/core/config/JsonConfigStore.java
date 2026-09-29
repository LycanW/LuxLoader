package dev.luxloader.core.config;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.config.ConfigEditor;
import dev.luxloader.api.config.ConfigOption;
import dev.luxloader.api.config.ConfigSchema;
import dev.luxloader.api.config.ConfigStore;
import dev.luxloader.api.config.ConfigView;
import dev.luxloader.core.util.SimpleJson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * JSON configuration facade with schema validation and ignored-key diagnostics. Invalid reads return
 * defaults. Writes use a temporary file, atomic replacement and .bak backup. Section views handle path
 * prefixes. Migration only replaces explicitly declared legacyDefault values, since storage cannot
 * distinguish old defaults from user choices.
 */
public final class JsonConfigStore implements ConfigStore {

    /**
     * File format version; increment when defaults or format require migration. Loading migrates only
     * older versions, then stores the current version to make the next startup idempotent.
     */
    public static final int SCHEMA_VERSION = 2;

    private final Path file;
    private Map<String, Object> root = new LinkedHashMap<>();
    private final List<IgnoredKey> ignored = new ArrayList<>();
    private String lastError = "";
    private boolean firstRun;
    private final Map<String, ConfigSchema> schemas = new LinkedHashMap<>();

    /**
     * Version read from disk during this load; -1 means absent. Keep it separate from fileVersion():
     * migrations update the document, but later-bound plugin sections still need the original version.
     */
    private int loadedFileVersion = -1;

    public JsonConfigStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
    }

    /** Bind a section schema for defaults and validation. */
    public void bindSchema(String sectionPath, ConfigSchema schema) {
        if (sectionPath == null || sectionPath.isBlank()) {
            schemas.put("", schema);
        } else {
            schemas.put(sectionPath, schema);
        }
    }

    @Override
    public Path file() {
        return file;
    }

    @Override
    public void load() {
        ignored.clear();
        lastError = "";
        firstRun = !java.nio.file.Files.exists(file);
        loadedFileVersion = -1;
        try {
            Map<String, Object> read = SimpleJson.readFile(file);
            root = read;
            // Capture the original disk version before any writes. Plugin schemas bind after load(), so every section in this session must use loadedFileVersion even after the document is stamped current. Otherwise the first migration prevents all later plugin migrations.
            loadedFileVersion = readVersion(read);
            if (loadedFileVersion >= 0 && loadedFileVersion < SCHEMA_VERSION) {
                // Update the saved version but retain loadedFileVersion for later section migrations in this session.
                root.put("_version", SCHEMA_VERSION);
            }
            migrateBoundSections();
            applyDefaults();
            validateAll();
            if (firstRun) {
                save();
            }
        } catch (IOException | SimpleJson.JsonException e) {
            lastError = tr("Failed to read configuration: ") + e.getMessage();
            // Back up malformed configuration and continue with defaults so the game can start.
            backupBrokenFile();
            root = new LinkedHashMap<>();
            loadedFileVersion = -1;
            applyDefaults();
        }
    }

    /** Read _version; return -1 when absent. */
    private int readVersion(Map<String, Object> data) {
        Object v = data.get("_version");
        if (v == null) {
            return -1;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        // Do not guess a malformed version and risk rewriting user values.
        ignored.add(new IgnoredKey("_version", v, tr("Version is not an integer; migration skipped")));
        return -1;
    }

    /** Migrate schemas already bound at load(), usually the loader's root schema. */
    private void migrateBoundSections() {
        for (Map.Entry<String, ConfigSchema> e : schemas.entrySet()) {
            migrateSection(e.getKey(), e.getValue());
        }
    }

    /**
     * Migrate declared historical defaults in a bound section. Call when plugin schemas bind after load().
     * Decide using loadedFileVersion, not the already-updated document. Leave newer-version files
     * untouched to preserve unknown data. Return the number of rewritten keys.
     */
    public int migrateSection(String sectionPath, ConfigSchema schema) {
        if (schema == null || !needsMigration()) {
            return 0;
        }
        String section = normalize(sectionPath);
        ConfigEditor editor = editor(section);
        int migrated = 0;
        for (ConfigOption<?> option : schema.options()) {
            if (option.legacyDefaults().isEmpty()) {
                continue;
            }
            String full = section.isEmpty() ? option.path() : section + "." + option.path();
            Object raw = rawAt(full);
            if (raw == null) {
                continue;
            }
            // Normalize before comparison: a parsed Long and declared Double are not equal even at the same numeric value. Coercion also rejects values outside the current range.
            Object value = option.coerce(raw);
            if (value == null || !option.legacyDefaults().contains(value)) {
                // Replace only exact historical defaults; preserve all other user choices.
                continue;
            }
            option.writeDefault(editor);
            migrated++;
            // Report migrations as ignored values so configuration changes remain visible.
            ignored.add(new IgnoredKey(full, raw,
                    tr("Historical default migrated to current default ") + option.defaultValue()));
        }
        return migrated;
    }

    /** Whether the loaded file is older. Files without a version do not migrate. */
    private boolean needsMigration() {
        // Missing _version can mean a newly generated or user-written file, not version zero. Only fill missing defaults and stamp the current version; do not rewrite explicit values as historical defaults.
        return loadedFileVersion >= 0 && loadedFileVersion < SCHEMA_VERSION;
    }

    private void backupBrokenFile() {
        try {
            if (java.nio.file.Files.exists(file)) {
                Path broken = file.resolveSibling(file.getFileName() + ".broken");
                java.nio.file.Files.move(file, broken,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                lastError += tr("(backed up to ") + broken.getFileName() + "）";
            }
        } catch (IOException e) {
            lastError += tr("(backup failed: ") + e.getMessage() + "）";
        }
    }

    /** Fill missing keys with schema defaults. */
    private void applyDefaults() {
        // Global section.
        ConfigSchema rootSchema = schemas.get("");
        if (rootSchema != null) {
            applyDefaultsFor("", rootSchema);
        }
        for (Map.Entry<String, ConfigSchema> e : schemas.entrySet()) {
            if (!e.getKey().isEmpty()) {
                applyDefaultsFor(e.getKey(), e.getValue());
            }
        }
        if (root.get("_version") == null) {
            root.put("_version", SCHEMA_VERSION);
        }
    }

    private void applyDefaultsFor(String sectionPath, ConfigSchema schema) {
        ConfigEditor editor = editor(sectionPath);
        for (ConfigOption<?> option : schema.options()) {
            // Use rawAt's hierarchical lookup. A flat lookup would treat existing nested keys as missing and overwrite user configuration with defaults.
            String full = sectionPath == null || sectionPath.isEmpty()
                    ? option.path()
                    : sectionPath + "." + option.path();
            if (rawAt(full) == null) {
                option.writeDefault(editor);
            }
        }
    }

    /** Validate section values against schemas and collect invalid entries. */
    private void validateAll() {
        for (Map.Entry<String, ConfigSchema> e : schemas.entrySet()) {
            ConfigView view = e.getKey().isEmpty() ? root() : section(e.getKey());
            for (ConfigOption<?> option : e.getValue().options()) {
                Optional<Object> raw = view.raw(option.path());
                if (raw.isEmpty()) {
                    continue;
                }
                if (option.coerce(raw.get()) == null) {
                    ignored.add(new IgnoredKey(
                            (e.getKey().isEmpty() ? "" : e.getKey() + ".") + option.path(),
                            raw.get(),
                            tr("Expected ") + option.typeName() + tr("; reverted to default")));
                }
            }
        }
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    public void save() {
        try {
            SimpleJson.writeFileAtomic(file, root, 2);
            lastError = "";
        } catch (IOException e) {
            lastError = tr("Failed to save configuration: ") + e.getMessage();
        }
    }

    @Override
    public ConfigView root() {
        return new View(root, "", true);
    }

    @Override
    public ConfigView section(String path) {
        return new View(navigate(path, true), normalize(path), true);
    }

    @Override
    public ConfigEditor editor(String path) {
        return new View(navigate(path, true), normalize(path), false);
    }

    @Override
    public boolean has(String path) {
        return rawAt(path) != null;
    }

    @Override
    public List<String> topLevelKeys() {
        return root.keySet().stream().filter(k -> !k.startsWith("_")).toList();
    }

    @Override
    public List<IgnoredKey> ignoredKeys() {
        return List.copyOf(ignored);
    }

    @Override
    public Optional<String> lastError() {
        return lastError.isEmpty() ? Optional.empty() : Optional.of(lastError);
    }

    @Override
    public int fileVersion() {
        Object v = root.get("_version");
        return v instanceof Number n ? n.intValue() : 0;
    }

    @Override
    public boolean isFirstRun() {
        return firstRun;
    }

    /** Underlying data for diagnostic reports. */
    public Map<String, Object> rawRoot() {
        return java.util.Collections.unmodifiableMap(root);
    }

    private static String normalize(String path) {
        return path == null || path.isBlank() ? "" : path.trim();
    }

    private Object rawAt(String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(part);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    /** Navigate to a section, creating it when requested. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> navigate(String path, boolean create) {
        if (path == null || path.isBlank()) {
            return root;
        }
        Map<String, Object> cur = root;
        for (String part : path.split("\\.")) {
            Object next = cur.get(part);
            if (next instanceof Map<?, ?> m) {
                cur = (Map<String, Object>) m;
            } else if (next == null && create) {
                Map<String, Object> fresh = new LinkedHashMap<>();
                cur.put(part, fresh);
                cur = fresh;
            } else if (next == null) {
                return new LinkedHashMap<>();
            } else {
                // A path collides with a non-object value: preserve user data and return a temporary empty map.
                return new LinkedHashMap<>();
            }
        }
        return cur;
    }

    /** Path-bound view implementing reads and writes, including existence checks before writing defaults. */
    private final class View implements ConfigView, ConfigEditor {

        private final Map<String, Object> backing;
        private final String prefix;
        private final boolean readOnly;

        View(Map<String, Object> backing, String prefix, boolean readOnly) {
            this.backing = backing;
            this.prefix = prefix;
            this.readOnly = readOnly;
        }

        private String fullPath(String path) {
            String local = ConfigView.requireValidPath(path);
            return prefix.isEmpty() ? local : prefix + "." + local;
        }

        /**
         * Read dotted paths hierarchically, matching nested writes. A flat backing.get(path) would miss
         * loader.enabled stored as {loader:{enabled:true}} and silently return defaults. Undotted
         * section-local paths remain direct lookups.
         */
        private Object lookup(String path) {
            String local = ConfigView.requireValidPath(path);
            if (local.indexOf('.') < 0) {
                return backing.get(local);
            }
            Object current = backing;
            for (String part : local.split("\\.")) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(part);
                if (current == null) {
                    return null;
                }
            }
            return current;
        }

        @Override
        public boolean getBoolean(String path, boolean fallback) {
            Object v = lookup(path);
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof String s) {
                ignore(path, v, tr("Expected boolean"));
                return Boolean.parseBoolean(s.trim());
            }
            if (v != null) {
                ignore(path, v, tr("Expected boolean"));
            }
            return fallback;
        }

        @Override
        public int getInt(String path, int fallback) {
            Object v = lookup(path);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v instanceof String s) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    ignore(path, v, tr("Expected integer"));
                    return fallback;
                }
            }
            if (v != null) {
                ignore(path, v, tr("Expected integer"));
            }
            return fallback;
        }

        @Override
        public long getLong(String path, long fallback) {
            Object v = lookup(path);
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v != null) {
                ignore(path, v, tr("Expected integer"));
            }
            return fallback;
        }

        @Override
        public float getFloat(String path, float fallback) {
            Object v = lookup(path);
            if (v instanceof Number n) {
                return n.floatValue();
            }
            if (v instanceof String s) {
                try {
                    return Float.parseFloat(s.trim());
                } catch (NumberFormatException e) {
                    ignore(path, v, tr("Expected number"));
                    return fallback;
                }
            }
            if (v != null) {
                ignore(path, v, tr("Expected number"));
            }
            return fallback;
        }

        @Override
        public String getString(String path, String fallback) {
            Object v = lookup(path);
            if (v == null) {
                return fallback;
            }
            if (v instanceof String s) {
                return s;
            }
            ignore(path, v, tr("Expected string"));
            return String.valueOf(v);
        }

        @Override
        public <E extends Enum<E>> E getEnum(String path, Class<E> type, E fallback) {
            Object v = lookup(path);
            if (v == null) {
                return fallback;
            }
            String s = String.valueOf(v).trim();
            for (E e : type.getEnumConstants()) {
                if (e.name().equalsIgnoreCase(s)) {
                    return e;
                }
            }
            ignore(path, v, tr("Unknown enum value (allowed: ") + java.util.Arrays.toString(type.getEnumConstants()) + "）");
            return fallback;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<String> getStringList(String path, List<String> fallback) {
            Object v = lookup(path);
            if (v instanceof List<?> l) {
                List<String> out = new ArrayList<>(l.size());
                for (Object o : l) {
                    out.add(String.valueOf(o));
                }
                return List.copyOf(out);
            }
            if (v instanceof String s) {
                return List.of(s.split("\\s*,\\s*"));
            }
            if (v != null) {
                ignore(path, v, tr("Expected string array"));
            }
            return fallback;
        }

        @Override
        public boolean has(String path) {
            return backing.containsKey(ConfigView.requireValidPath(path));
        }

        @Override
        public Optional<Object> raw(String path) {
            return Optional.ofNullable(lookup(path));
        }

        @Override
        public List<String> childKeys() {
            return List.copyOf(backing.keySet());
        }

        @Override
        public void set(String path, Object value) {
            if (readOnly) {
                throw new UnsupportedOperationException(tr("This view is read-only: ") + prefix);
            }
            String local = ConfigView.requireValidPath(path);
            int dot = local.indexOf('.');
            if (dot < 0) {
                backing.put(local, value);
                return;
            }
            // Create nested sections during writes.
            String head = local.substring(0, dot);
            String tail = local.substring(dot + 1);
            Object child = backing.get(head);
            if (!(child instanceof Map)) {
                Map<String, Object> fresh = new LinkedHashMap<>();
                backing.put(head, fresh);
                child = fresh;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) child;
            new View(m, prefix.isEmpty() ? head : prefix + "." + head, false).set(tail, value);
        }

        @Override
        public boolean remove(String path) {
            if (readOnly) {
                throw new UnsupportedOperationException(tr("This view is read-only: ") + prefix);
            }
            return backing.remove(ConfigView.requireValidPath(path)) != null;
        }

        @Override
        public String toString() {
            return "ConfigView[" + (prefix.isEmpty() ? "<root>" : prefix) + ", keys=" + backing.size() + "]";
        }

        private void ignore(String path, Object value, String reason) {
            ignored.add(new IgnoredKey(fullPath(path), value, reason));
        }
    }
}
