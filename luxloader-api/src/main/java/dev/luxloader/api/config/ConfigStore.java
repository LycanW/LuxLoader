package dev.luxloader.api.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Configuration storage under config/luxloader/: luxloader.json contains global and plugin sections;
 * reports/ contains diagnostic exports. Core uses its own small JSON implementation to avoid runtime
 * dependencies. Saves write a temporary file then atomically replace the original, backing up the
 * previous version as .bak.
 */
public interface ConfigStore {

    /** Configuration file path. */
    Path file();

    /** Load configuration or generate it on first use. */
    void load();

    /** Reload from disk; implementations decide whether to preserve unsaved edits (overwritten by default). */
    void reload();

    /** Save atomically with a backup. */
    void save();

    /** Root configuration view. */
    ConfigView root();

    /** Plugin section; missing sections return an empty view that supplies defaults. */
    ConfigView section(String path);

    /** Get a writable section; an empty path selects the root. */
    ConfigEditor editor(String path);

    /** Whether a key exists. */
    boolean has(String path);

    /** List top-level section names. */
    List<String> topLevelKeys();

    /**
     * Ignored keys and reasons, such as wrong types, spelling or ranges. Bad keys never prevent startup.
     * Report migrated historical defaults too, so configuration changes are visible to users.
     */
    List<IgnoredKey> ignoredKeys();

    /** Last load or save error, including I/O failures. */
    Optional<String> lastError();

    /** Schema version recorded in the file. */
    int fileVersion();

    /** An ignored key. */
    record IgnoredKey(String path, Object rawValue, String reason) {
        public String describe() {
            return path + " = " + rawValue + " —— " + reason;
        }
    }

    /** First run: no file existed and defaults were generated. */
    boolean isFirstRun();
}
