package dev.luxloader.core.plugin;

import dev.luxloader.api.plugin.PipelinePlugin;
import dev.luxloader.core.runtime.RecordingLifecyclePlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Classpath discovery must return one object per declared plugin. The classpath pass also reads
 * service declaration files directly, so without coordination the same class would be constructed
 * twice and the discarded object would never reach a lifecycle callback.
 */
class PluginLoaderConstructionTest {

    @Test
    @DisplayName("Classpath Discovery Constructs Each Plugin Once")
    void classpathDiscoveryConstructsEachPluginOnce() {
        // Reset the observation map so a discarded duplicate is visible in this call.
        RecordingLifecyclePlugin.reset();
        PluginLoader loader = new PluginLoader();

        List<PipelinePlugin> discovered = loader.discoverOnClasspath(
                PluginLoaderConstructionTest.class.getClassLoader());

        // The recording plugin observes its own construction, so object identity can be compared
        // against what discovery returned.
        List<RecordingLifecyclePlugin> recording = discovered.stream()
                .filter(RecordingLifecyclePlugin.class::isInstance)
                .map(RecordingLifecyclePlugin.class::cast)
                .toList();
        assertEquals(1, recording.size(),
                "Exactly one recording plugin object must reach the loader: " + recording.size());
        assertEquals(1, RecordingLifecyclePlugin.instances().size(),
                "Discovery must not construct a plugin object it then discards: "
                        + RecordingLifecyclePlugin.instances().keySet());

        RecordingLifecyclePlugin returned = recording.get(0);
        assertTrue(RecordingLifecyclePlugin.constructedOnce(returned),
                "Discovery must return the instance it constructed");

        // No other returned plugin may share a class with another entry.
        Map<Class<?>, PipelinePlugin> byClass = new IdentityHashMap<>();
        for (PipelinePlugin plugin : discovered) {
            assertNotNull(plugin);
            assertTrue(byClass.put(plugin.getClass(), plugin) == null,
                    "Duplicate discovery object for " + plugin.getClass().getName());
        }
    }
}
