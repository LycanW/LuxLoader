package dev.luxloader.core.plugin;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Optional packaging check against external plugin builds, without creating a GPU device. */
class StandalonePluginDiscoveryTest {
    @Test void externalArchivesUseTheHostApiAndDeclareTheCurrentAbi() {
        String directories = System.getProperty("luxloader.test.pluginDirs", "");
        assumeFalse(directories.isBlank(), "Supply directories containing independently built plugin JARs");
        for (String directory : directories.split(",")) {
            var loader = new PluginLoader();
            try {
                var plugins = loader.discoverPackages(Path.of(directory.trim()), getClass().getClassLoader());
                assertTrue(loader.errors().isEmpty(), () -> directory + ": " + loader.errors());
                assertTrue(loader.skipped().isEmpty(), () -> directory + ": " + loader.skipped());
                assertFalse(plugins.isEmpty(), directory);
                for (var discovered : plugins) {
                    assertNotNull(discovered.plugin().mod().id());
                    assertSame(dev.luxloader.api.plugin.PipelinePlugin.class,
                            assertDoesNotThrow(() -> Class.forName("dev.luxloader.api.plugin.PipelinePlugin",
                                    false, discovered.plugin().getClass().getClassLoader())));
                }
            } finally {
                loader.close();
            }
        }
    }
}
