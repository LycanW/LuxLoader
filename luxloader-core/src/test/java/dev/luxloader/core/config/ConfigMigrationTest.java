package dev.luxloader.core.config;

import dev.luxloader.api.config.ConfigOption;
import dev.luxloader.api.config.ConfigSchema;
import dev.luxloader.core.runtime.LoaderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for migrating historical defaults while retaining user overrides. A previous
 * maxDispatchGroups default of 4096 prevented 1080p dispatches requiring 8160 groups after the default
 * increased to 32768. Plugin schemas are bound after load(), so migrations must also run when each
 * schema is attached.
 */
class ConfigMigrationTest {

    /** Actual plugin section shape: plugins.<encoded plugin ID>, with dots encoded as underscores. */
    private static final String SECTION = "plugins.some_plugin";

    private static final String MAX_DISPATCH_GROUPS = "perf.maxDispatchGroups";
    private static final String SHARPNESS_AMOUNT = "sharpness.amount";
    private static final String WORKGROUP_SIZE = "perf.workgroupSize";

    @TempDir
    Path tempDir;

    /**
     * Schema matching the upscale example. maxDispatchGroups declares 4096 as a historical default and
     * 32768 as its current default; workgroupSize has no historical default and serves as a control.
     */
    private static ConfigSchema pluginSchema() {
        return ConfigSchema.builder()
                .add(ConfigOption.integer(MAX_DISPATCH_GROUPS, 32768)
                        .legacyDefault(4096)
                        .name("单帧最大派发组数")
                        .range(64, 65536)
                        .build())
                .add(ConfigOption.number(SHARPNESS_AMOUNT, 0.35)
                        .legacyDefault(0.2)
                        .range(0.0, 1.0)
                        .build())
                .add(ConfigOption.integer(WORKGROUP_SIZE, 16)
                        .name("工作组边长")
                        .range(8, 32)
                        .build())
                .build();
    }

    /** Open configuration in the same order as RenderDriverImpl.initialize(). */
    private JsonConfigStore openLikeHost() {
        JsonConfigStore store = new JsonConfigStore(tempDir.resolve("luxloader.json"));
        // Startup initially binds only the root schema.
        store.bindSchema("", LoaderConfig.schema());
        store.load();
        // Bind the plugin schema after discovery and apply its migration, as RenderDriverImpl.applySchemaDefaults does.
        ConfigSchema schema = pluginSchema();
        store.bindSchema(SECTION, schema);
        store.migrateSection(SECTION, schema);
        return store;
    }

    private void writeLegacyFile(String json) throws Exception {
        Files.writeString(tempDir.resolve("luxloader.json"), json);
    }

    @Test
    @DisplayName("Legacy Default Is Migrated")
    void legacyDefaultIsMigrated() throws Exception {
        // Version 1 is the configuration version used before this migration.
        writeLegacyFile("""
                {
                  "_version": 1,
                  "plugins": {
                    "some_plugin": {
                      "perf": { "maxDispatchGroups": 4096 },
                      "sharpness": { "amount": 0.2 }
                    }
                  }
                }
                """);

        JsonConfigStore store = openLikeHost();

        assertEquals(32768, store.section(SECTION).getInt(MAX_DISPATCH_GROUPS, -1),
                "Historical default 4096 must migrate to 32768");
        assertEquals(0.35, store.section(SECTION).getFloat(SHARPNESS_AMOUNT, -1f), 1e-6,
                "Historical defaults apply to both integer and floating-point values");
        assertEquals(JsonConfigStore.SCHEMA_VERSION, store.fileVersion(),
                "Completed migration must write the current version");
        assertTrue(store.ignoredKeys().stream()
                        .anyMatch(k -> k.path().equals(SECTION + "." + MAX_DISPATCH_GROUPS)
                                && k.reason().contains("迁移")),
                "Migration must be reported in diagnostics: " + store.ignoredKeys());
    }

    @Test
    @DisplayName("Deliberate Value Is Kept")
    void deliberateValueIsKept() throws Exception {
        writeLegacyFile("""
                {
                  "_version": 1,
                  "plugins": { "some_plugin": { "perf": { "maxDispatchGroups": 8192 } } }
                }
                """);

        JsonConfigStore store = openLikeHost();

        assertEquals(8192, store.section(SECTION).getInt(MAX_DISPATCH_GROUPS, -1),
                "8192 is a user override and must be preserved");
        assertTrue(store.ignoredKeys().isEmpty(),
                "No migration must produce no migration diagnostic: " + store.ignoredKeys());
    }

    @Test
    @DisplayName("Migration Is Idempotent")
    void migrationIsIdempotent() throws Exception {
        writeLegacyFile("""
                {
                  "_version": 1,
                  "plugins": { "some_plugin": { "perf": { "maxDispatchGroups": 4096 } } }
                }
                """);

        JsonConfigStore store = new JsonConfigStore(tempDir.resolve("luxloader.json"));
        store.bindSchema("", LoaderConfig.schema());
        store.load();
        ConfigSchema schema = pluginSchema();
        store.bindSchema(SECTION, schema);

        assertEquals(1, store.migrateSection(SECTION, schema), "The first migration must change one key");
        assertEquals(0, store.migrateSection(SECTION, schema),
                "Repeating migration in one session must change no keys");
        assertEquals(1, store.ignoredKeys().size(),
                "Repeated migration must not duplicate diagnostics: " + store.ignoredKeys());
        store.save();

        // On the next startup, the stored version is current and migration must not run again.
        JsonConfigStore reopened = openLikeHost();
        assertEquals(32768, reopened.section(SECTION).getInt(MAX_DISPATCH_GROUPS, -1));
        assertTrue(reopened.ignoredKeys().isEmpty(),
                "A second startup must not migrate again: " + reopened.ignoredKeys());
    }

    @Test
    @DisplayName("File Without Version Is Not Migrated")
    void fileWithoutVersionIsNotMigrated() throws Exception {
        writeLegacyFile("""
                { "plugins": { "some_plugin": { "perf": { "maxDispatchGroups": 4096 } } } }
                """);

        JsonConfigStore store = openLikeHost();

        assertEquals(4096, store.section(SECTION).getInt(MAX_DISPATCH_GROUPS, -1),
                "Without version metadata, preserve 4096 as a possible user choice");
        assertEquals(JsonConfigStore.SCHEMA_VERSION, store.fileVersion(),
                "applyDefaults must stamp new files with the current version");
    }

    @Test
    @DisplayName("Options Without Legacy Defaults Are Unchanged")
    void optionsWithoutLegacyDefaultsAreUnchanged() {
        ConfigSchema schema = pluginSchema();

        assertEquals(List.of(4096.0), schema.find(MAX_DISPATCH_GROUPS).orElseThrow().legacyDefaults());
        assertTrue(schema.find(WORKGROUP_SIZE).orElseThrow().legacyDefaults().isEmpty(),
                "Undeclared historical defaults must remain empty");
    }
}
