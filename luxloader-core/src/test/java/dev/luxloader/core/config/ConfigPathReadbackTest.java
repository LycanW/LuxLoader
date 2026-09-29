package dev.luxloader.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for nested configuration readback. Dot-separated paths must traverse nested maps,
 * matching set(), rather than looking up a flat key and silently returning defaults.
 */
class ConfigPathReadbackTest {

    @TempDir
    Path tempDir;

    private JsonConfigStore open() {
        JsonConfigStore store = new JsonConfigStore(tempDir.resolve("luxloader.json"));
        store.bindSchema("", dev.luxloader.core.runtime.LoaderConfig.schema());
        store.load();
        return store;
    }

    @Test
    @DisplayName("Dotted Path Round Trip")
    void dottedPathRoundTrip() {
        JsonConfigStore store = open();
        String path = dev.luxloader.core.runtime.LoaderConfig.DISABLED_PIPELINES;

        store.editor("").set(path, "a:b/c,d:e/f");
        store.save();

        assertEquals("a:b/c,d:e/f", store.root().getString(path, "读不到"),
                "Dot-separated paths must read nested configuration values");
    }

    @Test
    @DisplayName("Survives Reload")
    void survivesReload() throws Exception {
        String path = dev.luxloader.core.runtime.LoaderConfig.DISABLED_PIPELINES;
        JsonConfigStore first = open();
        first.editor("").set(path, "pipeline-one");
        first.save();

        JsonConfigStore second = open();

        assertEquals("pipeline-one", second.root().getString(path, "读不到"),
                "Values must survive reloading. lastError=" + second.lastError()
                        + " file=" + Files.readString(tempDir.resolve("luxloader.json")));
    }

    @Test
    @DisplayName("All Typed Getters Use Dotted Paths")
    void allTypedGettersUseDottedPaths() {
        JsonConfigStore store = open();

        // Change the schema defaults (true and 4000) to prove values come from the file.
        store.editor("").set("loader.autoActivate", false);
        store.editor("").set("diagnostics.maxLogLines", 1234);
        store.save();

        JsonConfigStore reopened = open();
        assertEquals(false, reopened.root().getBoolean("loader.autoActivate", true),
                "Boolean reads must traverse nested sections");
        assertEquals(1234, reopened.root().getInt("diagnostics.maxLogLines", 4000),
                "Integer reads must traverse nested sections");
    }

    @Test
    @DisplayName("Section View Short Paths Still Work")
    void sectionViewShortPathsStillWork() {
        JsonConfigStore store = open();

        // Keys inside section("loader") omit the section prefix.
        assertEquals(true, store.section("loader").getBoolean("enabled", false),
                "Section-local keys must resolve within that section");
    }

    @Test
    @DisplayName("Missing Path Falls Back")
    void missingPathFallsBack() {
        JsonConfigStore store = open();

        assertEquals("默认", store.root().getString("nope.missing.key", "默认"));
        assertEquals(7, store.root().getInt("loader.nope.deeper", 7));
        assertTrue(Files.exists(tempDir.resolve("luxloader.json")));
    }
}
