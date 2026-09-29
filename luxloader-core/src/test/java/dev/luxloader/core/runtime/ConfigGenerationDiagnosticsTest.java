package dev.luxloader.core.runtime;

import dev.luxloader.api.plugin.RenderDriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Print generated configuration for diagnosis while asserting its public structure, complementing
 * RenderDriverImplTest lifecycle coverage.
 */
class ConfigGenerationDiagnosticsTest {

    @TempDir
    Path configDir;

    @Test
    @DisplayName("Dump Generated Config")
    void dumpGeneratedConfig() throws Exception {
        RenderDriverImpl driver = new RenderDriverImpl(configDir);
        try {
            driver.initialize(RenderDriver.DeviceRequest.attachedToGame());
            driver.config().save();

            Path file = configDir.resolve("luxloader.json");
            assertTrue(Files.exists(file), "The configuration file must exist");

            String text = Files.readString(file);
            System.out.println("=== Generated configuration ===");
            System.out.println(text);
            System.out.println("=== End (" + text.length() + " characters) ===");

            // Plugin sections must use a single encoded key rather than a nested object tree.
            String encoded = RenderDriverImpl.encodePluginId(FakeTestPlugin.ID.toString());
            assertTrue(text.contains("\"" + encoded + "\""),
                    "Plugin sections must use a single key: " + encoded);
            System.out.println("Plugin section key = " + encoded);

            // Print top-level keys for manual structure inspection.
            System.out.println("Top-level keys: " + driver.config().topLevelKeys());
        } finally {
            driver.close();
        }
    }

    @Test
    @DisplayName("Plugin Id Encoding Is Safe")
    void pluginIdEncodingIsSafe() {
        assertEquals("dev_luxloader_test_fake-plugin",
                RenderDriverImpl.encodePluginId("dev.luxloader.test:fake-plugin"));
        assertEquals("a_b_c", RenderDriverImpl.encodePluginId("a.b:c"));
        assertEquals("", RenderDriverImpl.encodePluginId(null));
        // Encoded keys must contain no dots that could be interpreted as path separators.
        assertFalse(RenderDriverImpl.encodePluginId("a.b.c").contains("."));
        assertFalse(RenderDriverImpl.encodePluginId("dev.x:y").contains(":"));
    }
}
