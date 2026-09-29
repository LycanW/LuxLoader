package dev.luxloader.core.runtime;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.plugin.PipelineInfo;
import dev.luxloader.api.plugin.RenderDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests persistence of user-disabled pipelines across driver recreation without GPU work. */
class PipelineEnabledToggleTest {

    @TempDir
    Path configDir;

    private RenderDriverImpl driver;

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }

    private RenderDriverImpl newDriver() {
        Path content = configDir.resolveSibling("luxloader");
        RenderDriverImpl impl = new RenderDriverImpl(configDir, content);
        impl.initialize(RenderDriver.DeviceRequest.attachedToGame());
        return impl;
    }

    private static GpuId fakePipeline() {
        return FakeTestPlugin.ALWAYS_OK_PIPELINE;
    }

    @Test
    @DisplayName("Everything Enabled By Default")
    void everythingEnabledByDefault() {
        driver = newDriver();

        assertTrue(driver.isPipelineEnabled(fakePipeline()));
        assertTrue(driver.isPipelineEnabled(new GpuId("dev.luxloader.test", "anything")));
        assertFalse(driver.isPipelineEnabled(null), "Null must not count as enabled");
    }

    @Test
    @DisplayName("Disabling Persists Immediately")
    void disablingPersistsImmediately() throws Exception {
        driver = newDriver();

        assertTrue(driver.setPipelineEnabled(fakePipeline(), false));
        assertFalse(driver.isPipelineEnabled(fakePipeline()));

        String text = Files.readString(configDir.resolve("luxloader.json"));
        assertTrue(text.contains(fakePipeline().toString()),
                "Persist disabled pipelines in configuration: " + text);

        // Recreate the driver to simulate restart and verify the disabled state persists.
        driver.close();
        driver = newDriver();
        String after = Files.readString(configDir.resolve("luxloader.json"));
        assertFalse(driver.isPipelineEnabled(fakePipeline()),
                "Disabled state must survive restart. File contents: " + after);
    }

    @Test
    @DisplayName("Re Enabling Removes Entry")
    void reEnablingRemovesEntry() throws Exception {
        driver = newDriver();
        driver.setPipelineEnabled(fakePipeline(), false);
        driver.setPipelineEnabled(fakePipeline(), true);

        assertTrue(driver.isPipelineEnabled(fakePipeline()));
        String text = Files.readString(configDir.resolve("luxloader.json"));
        assertFalse(text.contains(fakePipeline().toString()),
                "Re-enabling must remove the disabled entry: " + text);
    }

    @Test
    @DisplayName("Handles Multiple Entries")
    void handlesMultipleEntries() {
        driver = newDriver();
        GpuId second = new GpuId("dev.luxloader.test", "second-pipeline");

        driver.setPipelineEnabled(fakePipeline(), false);
        driver.setPipelineEnabled(second, false);

        driver.close();
        driver = newDriver();

        assertFalse(driver.isPipelineEnabled(fakePipeline()));
        assertFalse(driver.isPipelineEnabled(second));
        assertTrue(driver.isPipelineEnabled(new GpuId("dev.luxloader.test", "third")),
                "Other pipelines must remain enabled");
    }

    @Test
    @DisplayName("Repeated Set Is No Op")
    void repeatedSetIsNoOp() {
        driver = newDriver();

        assertTrue(driver.setPipelineEnabled(fakePipeline(), false));
        assertTrue(driver.setPipelineEnabled(fakePipeline(), false));
        assertFalse(driver.isPipelineEnabled(fakePipeline()));

        assertTrue(driver.setPipelineEnabled(fakePipeline(), true));
        assertTrue(driver.setPipelineEnabled(fakePipeline(), true));
        assertTrue(driver.isPipelineEnabled(fakePipeline()));
    }

    @Test
    @DisplayName("Reflected In Pipeline State")
    void reflectedInPipelineState() {
        driver = newDriver();
        PipelineInfo before = driver.pipelines().stream()
                .filter(info -> info.id().equals(fakePipeline()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The test plugin must have registered its pipeline: " + driver.pipelines()));
        assertFalse(before.state() == PipelineInfo.State.DISABLED);

        driver.setPipelineEnabled(fakePipeline(), false);

        PipelineInfo after = driver.pipelines().stream()
                .filter(info -> info.id().equals(fakePipeline()))
                .findFirst()
                .orElseThrow();
        assertEquals(PipelineInfo.State.DISABLED, after.state(),
                "Expose the reason for deactivation: " + after);
        assertTrue(after.detail().contains("停用"), after.detail());
        assertTrue(after.isUnusable(), "Disabled pipelines must be unselectable while retaining readable tooltips");
    }

    @Test
    @DisplayName("Content Directory Is Separate")
    void contentDirectoryIsSeparate() {
        driver = newDriver();

        assertTrue(driver.contentDirectory().toString().endsWith("luxloader"));
        assertTrue(driver.pipelinesDirectory().toString().endsWith("pipelines"));
        assertTrue(driver.shaderCacheDirectory().toString().endsWith("shader-cache"));
        assertFalse(driver.pipelinesDirectory().equals(configDir.resolve("plugins")),
                "Plugin content must live outside config");
    }

    @Test
    @DisplayName("Infers Content Directory From Config Shape")
    void infersContentDirectoryFromConfigShape() {
        // The integration supplies <game directory>/config/luxloader.
        Path gameDir = configDir.resolve("fake-game");
        RenderDriverImpl impl = new RenderDriverImpl(gameDir.resolve("config").resolve("luxloader"));
        try {
            assertEquals(gameDir.resolve("luxloader"), impl.contentDirectory(),
                    "Resolve <game>/luxloader from <game>/config/luxloader");
        } finally {
            impl.close();
        }
    }

    @Test
    @DisplayName("Off Mode Persists")
    void offModePersists() throws Exception {
        driver = newDriver();
        assertTrue(driver.applyPipelineChoice(null, Map.of()));
        String saved = Files.readString(configDir.resolve("luxloader.json"));
        assertTrue(saved.contains("\"autoActivate\": false"), saved);
        driver.close();
        driver = newDriver();
        assertTrue(driver.configuredPipelineId().isEmpty());
    }

    @Test
    @DisplayName("Choice And Settings Persist")
    void choiceAndSettingsPersist() {
        driver = newDriver();
        GpuId id = fakePipeline();
        assertTrue(driver.pipelineOptionsSchema(id).orElseThrow().find("strength").isPresent());
        assertFalse(driver.applyPipelineChoice(id, Map.of("unknown", 1)));
        assertTrue(driver.applyPipelineChoice(id, Map.of("strength", 0.8)));
        driver.close();
        driver = newDriver();
        assertEquals(id, driver.configuredPipelineId().orElseThrow());
        assertEquals(0.8,
                driver.pipelineOptions(id).orElseThrow().getFloat("strength", 0f), 0.0001);
    }
}
