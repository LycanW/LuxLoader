package dev.luxloader.core.plugin;

import dev.luxloader.core.runtime.FakeTestPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.jar.Attributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests discovery for all supported package forms using service descriptors referencing FakeTestPlugin
 * on the parent test classpath. This exercises package discovery without compiling Java during the
 * test and verifies that plugins share the loader's API classes.
 */
class PluginPackageDiscoveryTest {

    /** Service descriptor path. */
    private static final String SERVICE = "META-INF/services/dev.luxloader.api.plugin.PipelinePlugin";

    /**
     * A different plugin class claiming the same ID as FakeTestPlugin. Different classes sharing an ID
     * must report a conflict; repeated discovery of the same class is deduplicated silently.
     */
    public static final class DuplicateIdPlugin implements dev.luxloader.api.plugin.PipelinePlugin {

        @Override
        public dev.luxloader.api.LuxMod mod() {
            return dev.luxloader.api.LuxMod.builder(
                            dev.luxloader.api.GpuId.parse(FakeTestPlugin.ID.toString()),
                            "冒名的假插件", "9.9.9")
                    .build();
        }

        @Override
        public void onLoad(dev.luxloader.api.plugin.PluginBootstrap bootstrap) {
            // Deduplication rejects this entry before it can run.
        }
    }

    /**
     * Store test packages under build/ rather than @TempDir. On Windows with JDK 25, jar URL resource
     * caching can retain file handles after URLClassLoader.close(), preventing immediate
     * temporary-directory cleanup. Gradle clean handles these files after the test JVM exits.
     */
    private static Path newPluginRoot() throws IOException {
        Path root = Path.of("build", "test-plugin-packages",
                "case-" + System.nanoTime());
        return Files.createDirectories(root);
    }

    /** Write a JAR or ZIP containing only a service descriptor. */
    private static Path writeArchive(Path target, String pluginClassName) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(out, currentManifest())) {
            jar.putNextEntry(new JarEntry(SERVICE));
            jar.write((pluginClassName + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return target;
    }

    private static Path writeArchiveWith(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(out, currentManifest())) {
            jar.putNextEntry(new JarEntry(SERVICE));
            jar.write(content.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return target;
    }

    private static Manifest currentManifest() {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(PluginLoader.PIPELINE_ABI_ATTRIBUTE,
                Integer.toString(PluginLoader.PIPELINE_ABI));
        return manifest;
    }

    @Test
    @DisplayName("Rejects Old Pipeline Archive")
    void rejectsOldPipelineArchive() throws IOException {
        Path root = newPluginRoot();
        Path legacy = root.resolve("legacy.jar");
        try (OutputStream out = Files.newOutputStream(legacy);
             JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry(SERVICE));
            jar.write((FakeTestPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        PluginLoader pluginLoader = loader();
        assertTrue(pluginLoader.discoverPackages(root, getClass().getClassLoader()).isEmpty());
        assertEquals(0, pluginLoader.createdLoaderCount());
        assertTrue(pluginLoader.skipped().stream().anyMatch(s -> s.contains("legacy.jar")
                && s.contains("ABI")));
    }

    private PluginLoader loader() {
        created.add(pluginLoader = new PluginLoader());
        return pluginLoader;
    }

    /** Loaders to close after each test. */
    private final List<PluginLoader> created = new java.util.ArrayList<>();
    private PluginLoader pluginLoader;

    /** Close loaders to release their archive handles. */
    @org.junit.jupiter.api.AfterEach
    void closeLoaders() {
        for (PluginLoader each : created) {
            each.close();
        }
        created.clear();
    }

    @Test
    @DisplayName("Close Clears Loader Cache")
    void closeClearsLoaderCache() throws IOException {
        Path root = newPluginRoot();
        Path jar = writeArchive(root.resolve("p.jar"), FakeTestPlugin.class.getName());

        PluginLoader pluginLoader = loader();
        pluginLoader.discoverPackages(root, getClass().getClassLoader());
        assertEquals(1, pluginLoader.cachedLoaderCount());

        pluginLoader.close();

        assertEquals(0, pluginLoader.cachedLoaderCount(), "Closing must clear the cache");
        assertEquals(0, pluginLoader.createdLoaderCount());
        assertTrue(jar.toFile().isFile(), "Closing class loaders must not delete files");

        // Scanning must still work after close, as required for plugin reload.
        pluginLoader.discoverPackages(root, getClass().getClassLoader());
        assertEquals(1, pluginLoader.cachedLoaderCount(), "Scanning after close must rebuild the cache");
    }

    @Test
    @DisplayName("Close Releases Archive Handles")
    void closeReleasesArchiveHandles() throws IOException {
        Path root = newPluginRoot();
        Path jar = writeArchive(root.resolve("p.jar"), FakeTestPlugin.class.getName());

        PluginLoader pluginLoader = loader();
        pluginLoader.discoverPackages(root, getClass().getClassLoader());

        pluginLoader.close();

        assertTrue(Files.deleteIfExists(jar),
                "Plugin JARs must be deletable after closing the loader. "
                        + "Reading service descriptors through jar URL enumeration previously retained handles"
                        + " until JVM exit and prevented plugin deletion.");
    }

    @Test
    @DisplayName("Classpath Channel Sees Parent Plugins")
    void classpathChannelSeesParentPlugins() throws IOException {
        Path root = newPluginRoot();
        Path jar = writeArchive(root.resolve("empty-declaration.jar"), "");

        ClassLoader child = new java.net.URLClassLoader(
                new java.net.URL[] {jar.toUri().toURL()}, getClass().getClassLoader());

        // Parent-first resource enumeration can report classpath plugins for an unrelated empty archive, assigning the wrong source and data directory. Package discovery must isolate service descriptors to the package.
        List<dev.luxloader.api.plugin.PipelinePlugin> leaked = loader().discoverOnClasspath(child);
        assertFalse(leaked.isEmpty(),
                "Classpath discovery may include parent service declarations; archive discovery must not");
        assertTrue(leaked.stream().anyMatch(p -> p instanceof FakeTestPlugin),
                "Classpath discovery must find the test plugin");
        assertEquals(0, loader().discoverPackages(root, getClass().getClassLoader()).size(),
                "Archive-local discovery must find nothing in an archive without a service descriptor");
    }

    // Supported package forms.

    @Test
    @DisplayName("Discovers Loose Jar")
    void discoversLooseJar() throws IOException {
        Path root = newPluginRoot();
        Path jar = writeArchive(root.resolve("my-pipeline.jar"), FakeTestPlugin.class.getName());

        List<PluginLoader.Discovered> found = loader().discoverPackages(root, getClass().getClassLoader());

        assertEquals(1, found.size(), "Expected one plugin: " + found);
        PluginLoader.Discovered discovered = found.get(0);
        assertEquals("my-pipeline.jar", discovered.source(),
                "The source must identify its file for UI and diagnostics");
        assertEquals(root, discovered.dataDirectory(),
                "Single-file packages use the plugin root as their data directory");
        assertTrue(discovered.plugin() instanceof FakeTestPlugin);
        assertEquals(FakeTestPlugin.ID.toString(), discovered.plugin().mod().id().toString());
        assertTrue(discovered.describe().contains("my-pipeline.jar"), discovered.describe());

        assertTrue(jar.toFile().isFile());
    }

    @Test
    @DisplayName("Discovers Zip As Alias")
    void discoversZipAsAlias() throws IOException {
        Path root = newPluginRoot();
        writeArchive(root.resolve("my-pipeline.zip"), FakeTestPlugin.class.getName());

        List<PluginLoader.Discovered> found = loader().discoverPackages(root, getClass().getClassLoader());

        assertEquals(1, found.size(), "ZIP and JAR discovery must behave identically: " + found);
        assertEquals("my-pipeline.zip", found.get(0).source());
    }

    @Test
    @DisplayName("Discovers Package Directory")
    void discoversPackageDirectory() throws IOException {
        Path root = newPluginRoot();
        Path packageDir = Files.createDirectories(root.resolve("my-pipeline"));
        writeArchive(packageDir.resolve("pipeline.jar"), FakeTestPlugin.class.getName());

        List<PluginLoader.Discovered> found = loader().discoverPackages(root, getClass().getClassLoader());

        assertEquals(1, found.size(), found.toString());
        PluginLoader.Discovered discovered = found.get(0);
        assertEquals("my-pipeline/pipeline.jar", discovered.source(),
                "Include the parent directory to distinguish equally named archives");
        assertEquals(packageDir, discovered.dataDirectory(),
                "Directory packages must expose their resources through dataDirectory()");
    }

    @Test
    @DisplayName("Discovers Multiple Archives In One Directory")
    void discoversMultipleArchivesInOneDirectory() throws IOException {
        Path root = newPluginRoot();
        Path packageDir = Files.createDirectories(root.resolve("bundle"));
        // Different classes claiming one plugin ID must produce a conflict diagnostic; repeated declarations of the same class are silently deduplicated.
        writeArchive(packageDir.resolve("a.jar"), FakeTestPlugin.class.getName());
        writeArchive(packageDir.resolve("b.jar"), DuplicateIdPlugin.class.getName());

        PluginLoader pluginLoader = loader();
        List<PluginLoader.Discovered> found = pluginLoader.discoverPackages(
                root, getClass().getClassLoader());

        assertEquals(1, found.size(), "Keep only one entry per plugin ID: " + found);
        assertEquals("bundle/a.jar", found.get(0).source(), "Keep the first entry in filename order");
        assertTrue(pluginLoader.skipped().stream().anyMatch(s -> s.contains("插件 id 冲突")),
                "Conflicting IDs must produce a diagnostic: " + pluginLoader.skipped());
    }

    // Content directory layout.

    @Test
    @DisplayName("Tolerates Unusable Entries")
    void toleratesUnusableEntries() throws IOException {
        Path root = newPluginRoot();
        Files.writeString(root.resolve("readme.txt"), "不是归档");
        Files.createDirectories(root.resolve("empty-dir"));
        // The archive contains no service descriptor.
        try (OutputStream out = Files.newOutputStream(root.resolve("not-a-plugin.jar"));
             JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("some/Class.class"));
            jar.write(new byte[] {1, 2, 3});
            jar.closeEntry();
        }

        PluginLoader pluginLoader = loader();
        List<PluginLoader.Discovered> found = pluginLoader.discoverPackages(
                root, getClass().getClassLoader());

        assertTrue(found.isEmpty(), "These entries must not be treated as plugins: " + found);
        assertTrue(pluginLoader.errors().isEmpty(), "Expected no errors: " + pluginLoader.errors());
        assertTrue(pluginLoader.skipped().stream().anyMatch(s -> s.contains("empty-dir")),
                "Report empty package directories without treating them as errors: " + pluginLoader.skipped());
        assertTrue(pluginLoader.skipped().stream().anyMatch(s -> s.contains("not-a-plugin.jar")),
                "Report the missing service descriptor: " + pluginLoader.skipped());
    }

    @Test
    @DisplayName("Missing Root Is Harmless")
    void missingRootIsHarmless() {
        PluginLoader pluginLoader = loader();

        assertTrue(pluginLoader.discoverPackages(Path.of("build", "no-such-plugin-dir"),
                getClass().getClassLoader()).isEmpty());
        assertTrue(pluginLoader.discoverPackages(null, getClass().getClassLoader()).isEmpty());
        assertTrue(pluginLoader.errors().isEmpty());
    }

    @Test
    @DisplayName("Broken Archive Is Skipped")
    void brokenArchiveIsSkipped() throws IOException {
        Path root = newPluginRoot();
        Files.writeString(root.resolve("broken.jar"), "这根本不是 zip");
        writeArchive(root.resolve("good.jar"), FakeTestPlugin.class.getName());

        PluginLoader pluginLoader = loader();
        List<PluginLoader.Discovered> found = pluginLoader.discoverPackages(
                root, getClass().getClassLoader());

        assertEquals(1, found.size(), "The valid package must still be discovered: " + found);
        assertEquals("good.jar", found.get(0).source());
        assertFalse(pluginLoader.errors().isEmpty(), "Record the invalid archive");
    }

    // Class loader reuse.

    @Test
    @DisplayName("Reuses Loader When Unchanged")
    void reusesLoaderWhenUnchanged() throws IOException {
        Path root = newPluginRoot();
        writeArchive(root.resolve("p.jar"), FakeTestPlugin.class.getName());

        PluginLoader pluginLoader = loader();
        pluginLoader.discoverPackages(root, getClass().getClassLoader());
        int afterFirst = pluginLoader.createdLoaderCount();

        pluginLoader.discoverPackages(root, getClass().getClassLoader());
        pluginLoader.discoverPackages(root, getClass().getClassLoader());

        assertEquals(afterFirst, pluginLoader.createdLoaderCount(),
                "Reloading an unchanged package must reuse its class loader");
        assertEquals(1, afterFirst);
        assertEquals(1, pluginLoader.cachedLoaderCount(), "Exactly one loader remains active");
    }

    @Test
    @DisplayName("Recreates Loader When File Changes")
    void recreatesLoaderWhenFileChanges() throws IOException {
        Path root = newPluginRoot();
        Path jar = writeArchive(root.resolve("p.jar"), FakeTestPlugin.class.getName());

        PluginLoader pluginLoader = loader();
        pluginLoader.discoverPackages(root, getClass().getClassLoader());
        assertEquals(1, pluginLoader.createdLoaderCount());

        // Change contents and mtime explicitly; writes within the same millisecond may appear unchanged.
        writeArchiveWith(jar, FakeTestPlugin.class.getName() + "\n");
        Files.setLastModifiedTime(jar,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));

        pluginLoader.discoverPackages(root, getClass().getClassLoader());

        assertEquals(2, pluginLoader.createdLoaderCount(),
                "Changed files must receive a new class loader");
        assertEquals(1, pluginLoader.cachedLoaderCount(),
                "Replacing the active loader keeps the active count at one");
    }

    // Consistency across service discovery channels.

    @Test
    @DisplayName("Both Apis Agree")
    void bothApisAgree() throws IOException {
        Path root = newPluginRoot();
        writeArchive(root.resolve("p.jar"), FakeTestPlugin.class.getName());

        assertEquals(
                loader().discoverPackages(root, getClass().getClassLoader()).size(),
                loader().discoverInDirectory(root, getClass().getClassLoader()).size(),
                "Both APIs must report the same discovery result");
        assertFalse(loader().discoverInDirectory(root, getClass().getClassLoader()).isEmpty());
    }

    @Test
    @DisplayName("Different Parent Gets Fresh Loader")
    void differentParentGetsFreshLoader() throws IOException {
        Path root = newPluginRoot();
        writeArchive(root.resolve("p.jar"), FakeTestPlugin.class.getName());

        PluginLoader pluginLoader = loader();
        pluginLoader.discoverPackages(root, getClass().getClassLoader());
        int before = pluginLoader.createdLoaderCount();

        ClassLoader otherParent = new java.net.URLClassLoader(new java.net.URL[0], null);
        pluginLoader.discoverPackages(root, otherParent);

        assertNotEquals(before, pluginLoader.createdLoaderCount(),
                "A different parent loader requires a new plugin class loader");
    }
}
