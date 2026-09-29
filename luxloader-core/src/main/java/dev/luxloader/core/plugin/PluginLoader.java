package dev.luxloader.core.plugin;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.LuxMod;
import dev.luxloader.api.plugin.PipelinePlugin;
import dev.luxloader.core.util.SimpleJson;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Discovers plugins from classpath ServiceLoader declarations, content packages under
 * luxloader/pipelines, or explicit host registration. Packages may be JAR/ZIP files or directories
 * containing archives plus shader/resource files. Nested archives inside packages are unsupported; mod
 * loaders handle their own nested JARs. <p>Parent-first URLClassLoaders share the host API. Cache by
 * path, modification time and size; changed archives need new loaders. Retain replaced loaders until
 * shutdown because registered classes may lazily load more dependencies. Repeated archive updates
 * therefore accumulate loaders until shutdown. Isolate individual plugin failures and report them
 * without aborting discovery.
 */
public final class PluginLoader {

    /** ServiceLoader interface name. */
    public static final String SERVICE_FILE =
            "META-INF/services/dev.luxloader.api.plugin.PipelinePlugin";

    /** External pipeline archives must opt in to the current scene/command contract. */
    public static final String PIPELINE_ABI_ATTRIBUTE = "LuxLoader-Pipeline-ABI";
    public static final int PIPELINE_ABI = 2;

    /** Service declaration filename without META-INF/services/. */
    private static final String SERVICE_NAME = "dev.luxloader.api.plugin.PipelinePlugin";

    /** Recognized plugin archive extensions. */
    private static final java.util.Set<String> ARCHIVE_SUFFIXES =
            java.util.Set.of(".jar", ".zip");

    private final List<String> errors = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();

    /**
     * Current loaders by absolute archive path. Replace after file changes because an open URLClassLoader
     * does not reread its archive.
     */
    private final Map<Path, CachedLoader> activeLoaders = new LinkedHashMap<>();

    /**
     * All created loaders, including replaced ones retained for shutdown cleanup and diagnostics. Existing
     * plugin objects may still need them.
     */
    private final List<java.net.URLClassLoader> allLoaders = new ArrayList<>();

    private record CachedLoader(long modifiedAt, long size, java.net.URLClassLoader loader) {
        boolean matches(Path jar) {
            try {
                return modifiedAt == Files.getLastModifiedTime(jar).toMillis()
                        && size == Files.size(jar);
            } catch (IOException e) {
                return false;
            }
        }
    }

    /**
     * Discovered plugin and origin.
     * @param plugin instance
     * @param source diagnostic filename/directory
     * @param dataDirectory package directory, or parent directory for a standalone archive; exposed
     * through bootstrap.dataDirectory()
     */
    public record Discovered(PipelinePlugin plugin, String source, Path dataDirectory) {

        public Discovered {
            source = source == null ? "" : source;
        }

        /** Diagnostic summary. */
        public String describe() {
            String id;
            try {
                id = plugin.mod() == null ? plugin.getClass().getName() : plugin.mod().id().toString();
            } catch (RuntimeException e) {
                id = plugin.getClass().getName();
            }
            return id + " ← " + source;
        }
    }

    /** Discovers classpath plugins. */
    public List<PipelinePlugin> discoverOnClasspath(ClassLoader loader) {
        List<PipelinePlugin> found = new ArrayList<>();
        List<Class<?>> instantiated = new ArrayList<>();
        try {
            ServiceLoader<PipelinePlugin> services = ServiceLoader.load(PipelinePlugin.class, loader);
            for (PipelinePlugin plugin : services) {
                if (plugin != null) {
                    found.add(plugin);
                    instantiated.add(plugin.getClass());
                }
            }
        } catch (ServiceConfigurationError e) {
            errors.add(tr("ServiceLoader configuration error: ") + e.getMessage());
        } catch (RuntimeException e) {
            errors.add(tr("ServiceLoader loading error: ") + e);
        }

        // Also enumerate service resources for packaging environments where ServiceLoader misses nested mod JAR declarations.
        found.addAll(discoverFromServiceFiles(loader, instantiated));
        return dedupeByName(found);
    }

    /** Deduplicates plugin IDs discovered through multiple channels. */
    private List<PipelinePlugin> dedupeByName(List<PipelinePlugin> plugins) {
        Map<String, PipelinePlugin> byId = new LinkedHashMap<>();
        for (PipelinePlugin p : plugins) {
            String id;
            try {
                LuxMod mod = p.mod();
                id = mod == null ? p.getClass().getName() : mod.id().toString();
            } catch (RuntimeException e) {
                errors.add(p.getClass().getName() + tr(" mod() threw: ") + e);
                continue;
            }
            byId.putIfAbsent(id, p);
        }
        return List.copyOf(byId.values());
    }

    /**
     * Reads service declaration resources directly to support mod classloaders with inconsistent
     * nested-JAR ServiceLoader enumeration.
     *
     * <p>Classes already returned by the primary ServiceLoader pass are not constructed again. A
     * discarded second object would never reach the host, so it could never be unloaded, and its
     * constructor-level registrations or native handles would leak. The lookup below only succeeds
     * because the primary pass loads the classes first; reordering the two passes would construct a
     * second object for every classpath plugin.
     * @param loader host class loader
     * @param alreadyInstantiated classes the primary ServiceLoader pass already returned
     */
    private List<PipelinePlugin> discoverFromServiceFiles(ClassLoader loader,
                                                          List<Class<?>> alreadyInstantiated) {
        List<PipelinePlugin> out = new ArrayList<>();
        Set<String> classNames = new LinkedHashSet<>();
        try {
            Enumeration<URL> resources = loader.getResources(SERVICE_FILE);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (InputStream in = url.openStream()) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    for (String line : text.split("\\R")) {
                        String trimmed = line.strip();
                        int hash = trimmed.indexOf('#');
                        if (hash >= 0) {
                            trimmed = trimmed.substring(0, hash).strip();
                        }
                        if (!trimmed.isEmpty()) {
                            classNames.add(trimmed);
                        }
                    }
                } catch (IOException e) {
                    errors.add(tr("Failed to read ") + url + tr("PluginLoader.5a4f562bd9", ": ") + e.getMessage());
                }
            }
        } catch (IOException e) {
            // Enumeration failure is nonfatal; ServiceLoader may already have succeeded.
            skipped.add(tr("Cannot enumerate ") + SERVICE_FILE + "（" + e.getMessage() + tr("); using ServiceLoader results only"));
        }

        for (String name : classNames) {
            try {
                Class<?> cls = Class.forName(name, false, loader);
                if (!PipelinePlugin.class.isAssignableFrom(cls)) {
                    errors.add(name + tr(" does not implement PipelinePlugin"));
                    continue;
                }
                if (alreadyInstantiated.contains(cls)) {
                    continue;
                }
                PipelinePlugin instance = (PipelinePlugin) cls.getDeclaredConstructor().newInstance();
                out.add(instance);
            } catch (ReflectiveOperationException | LinkageError e) {
                errors.add(tr("Failed to instantiate ") + name + tr("PluginLoader.5a4f562bd9", ": ") + e);
            }
        }
        return out;
    }

    /**
     * Scans standalone JAR/ZIP files and directories containing archives/resources.
     * @param root game luxloader/pipelines directory
     * @param parent host loader, required to share API class identity
     */
    public List<Discovered> discoverPackages(Path root, ClassLoader parent) {
        List<Discovered> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }

        List<Path> entries;
        try (var stream = Files.list(root)) {
            entries = stream.sorted().toList();
        } catch (IOException e) {
            errors.add(tr("Failed to scan plugin directory: ") + root + " — " + e.getMessage());
            return out;
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                // Directory package: all archives share this data directory.
                List<Path> archives = listArchives(entry);
                if (archives.isEmpty()) {
                    skipped.add(name + tr("/ contains no .jar or .zip (ignore if this is an author's working directory)"));
                    continue;
                }
                for (Path archive : archives) {
                    out.addAll(loadArchive(archive, entry, parent, name + "/"));
                }
            } else if (isArchive(name)) {
                // Standalone archive: its parent is the data directory.
                out.addAll(loadArchive(entry, root, parent, ""));
            }
        }
        return dedupeDiscovered(out);
    }

    /**
     * Compatibility archive-discovery entry point, also supporting directory packages.
     * @param directory plugin directory
     * @return successfully loaded plugins
     */
    public List<PipelinePlugin> discoverInDirectory(Path directory, ClassLoader parent) {
        return discoverPackages(directory, parent).stream()
                .map(Discovered::plugin)
                .toList();
    }

    /** Lists archives sorted by name for deterministic load order. */
    private List<Path> listArchives(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> isArchive(p.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            errors.add(tr("Failed to scan ") + directory + tr("PluginLoader.5a4f562bd9", ": ") + e.getMessage());
            return List.of();
        }
    }

    /** Accepts both JAR and ZIP archives. */
    private static boolean isArchive(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : ARCHIVE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Loads every plugin declared by an archive. */
    private List<Discovered> loadArchive(Path archive, Path dataDirectory, ClassLoader parent,
                                         String prefix) {
        List<Discovered> out = new ArrayList<>();
        try {
            if (!supportsCurrentAbi(archive)) {
                return out;
            }
            ClassLoader loader = loaderFor(archive, parent);
            List<PipelinePlugin> plugins = loadDeclaredIn(archive, loader);
            if (plugins.isEmpty()) {
                skipped.add(prefix + archive.getFileName() + tr(" contains no PipelinePlugin declaration")
                        + tr(" (check META-INF/services/") + SERVICE_NAME + "）");
                return out;
            }
            String source = prefix + archive.getFileName();
            for (PipelinePlugin plugin : plugins) {
                out.add(new Discovered(plugin, source, dataDirectory));
            }
        } catch (IOException e) {
            errors.add(tr("Failed to load ") + archive.getFileName() + tr("PluginLoader.5a4f562bd9", ": ") + e.getMessage());
        }
        return out;
    }

    private boolean supportsCurrentAbi(Path archive) throws IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(archive.toFile())) {
            var manifest = jar.getManifest();
            String declared = manifest == null ? null : manifest.getMainAttributes()
                    .getValue(PIPELINE_ABI_ATTRIBUTE);
            if (!Integer.toString(PIPELINE_ABI).equals(declared)) {
                skipped.add(archive.getFileName() + tr(" uses an incompatible pipeline ABI (declared=")
                        + (declared == null ? tr("missing") : declared) + tr(", required=") + PIPELINE_ABI
                        + tr("); install a plugin rebuilt against the current API"));
                return false;
            }
            return true;
        }
    }

    /**
     * Reads only this archive's own service declarations. Child-loader resource enumeration also returns
     * parent declarations, which would duplicate classpath plugins and assign unrelated package
     * origins/data directories. Read ZIP entries directly; resolve classes through the parent-first child
     * loader so plugins share the host API.
     */
    private List<PipelinePlugin> loadDeclaredIn(Path archive, ClassLoader loader)
            throws IOException {
        List<String> classNames = new ArrayList<>();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(archive.toFile())) {
            java.util.jar.JarEntry entry = jar.getJarEntry(SERVICE_FILE);
            if (entry == null) {
                return List.of();
            }
            try (InputStream in = jar.getInputStream(entry)) {
                classNames.addAll(parseServiceFile(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }

        List<PipelinePlugin> out = new ArrayList<>();
        for (String name : classNames) {
            try {
                Class<?> cls = Class.forName(name, false, loader);
                if (!PipelinePlugin.class.isAssignableFrom(cls)) {
                    errors.add(name + tr(" does not implement PipelinePlugin"));
                    continue;
                }
                out.add((PipelinePlugin) cls.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException | LinkageError e) {
                errors.add(tr("Failed to instantiate ") + name + tr("PluginLoader.5a4f562bd9", ": ") + e);
            }
        }
        return out;
    }

    /** Parses service declarations, ignoring blank lines and # comments. */
    private static List<String> parseServiceFile(String text) {
        List<String> names = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            int hash = trimmed.indexOf('#');
            if (hash >= 0) {
                trimmed = trimmed.substring(0, hash).strip();
            }
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    /** Reuses an unchanged archive's loader, avoiding repeated allocation on ordinary reloads. */
    private ClassLoader loaderFor(Path archive, ClassLoader parent) throws IOException {
        Path key = archive.toAbsolutePath().normalize();
        long modifiedAt = Files.getLastModifiedTime(key).toMillis();
        long size = Files.size(key);

        CachedLoader cached = activeLoaders.get(key);
        if (cached != null && cached.matches(key) && cached.loader().getParent() == parent) {
            return cached.loader();
        }
        java.net.URLClassLoader loader =
                new java.net.URLClassLoader(new java.net.URL[] {key.toUri().toURL()}, parent);
        // Keep replaced loaders open for registered pipelines' lazy class loading; close them at overall shutdown.
        activeLoaders.put(key, new CachedLoader(modifiedAt, size, loader));
        allLoaders.add(loader);
        return loader;
    }

    /** Current reusable loader count. */
    public int cachedLoaderCount() {
        return activeLoaders.size();
    }

    /**
     * All loaders created, including replacements retained until shutdown. Growth indicates repeated
     * archive updates.
     */
    public int createdLoaderCount() {
        return allLoaders.size();
    }

    /**
     * Closes all loaders and releases archive handles, particularly Windows file locks. Direct ZIP reads
     * avoid persistent JDK jar: URL cache locks. Call only when plugins are no longer used: loaded classes
     * remain valid, but future class/resource loading fails after close. Do not close during reload while
     * pending requests may reference old plugins.
     */
    public void close() {
        for (java.net.URLClassLoader loader : allLoaders) {
            try {
                loader.close();
            } catch (IOException | RuntimeException e) {
                // Cleanup failure must not prevent shutdown.
                skipped.add(tr("Failed to close plugin class loader: ") + e.getMessage());
            }
        }
        allLoaders.clear();
        activeLoaders.clear();
    }

    /** Deduplicates IDs, retaining the first discovery according to channel order. */
    private List<Discovered> dedupeDiscovered(List<Discovered> plugins) {
        Map<String, Discovered> byId = new LinkedHashMap<>();
        for (Discovered discovered : plugins) {
            String id;
            try {
                LuxMod mod = discovered.plugin().mod();
                id = mod == null ? discovered.plugin().getClass().getName() : mod.id().toString();
            } catch (RuntimeException e) {
                errors.add(discovered.plugin().getClass().getName() + tr(" mod() threw: ") + e);
                continue;
            }
            Discovered existing = byId.putIfAbsent(id, discovered);
            if (existing != null && existing.plugin().getClass() != discovered.plugin().getClass()) {
                skipped.add(tr("Conflicting plugin ID: ") + id + tr(" (keeping ") + existing.source()
                        + tr(", ignoring ") + discovered.source() + "）");
            }
        }
        return List.copyOf(byId.values());
    }

    /**
     * Topologically orders declared dependencies. Skip plugins with missing dependencies or cycles and
     * report reasons without aborting all loading.
     * @return safe load order
     */
    public List<PipelinePlugin> sortByDependencies(List<PipelinePlugin> plugins) {
        Map<String, PipelinePlugin> byId = new LinkedHashMap<>();
        for (PipelinePlugin p : plugins) {
            byId.put(p.mod().id().toString(), p);
        }

        List<PipelinePlugin> ordered = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> done = new LinkedHashSet<>();

        for (PipelinePlugin p : plugins) {
            visit(p, byId, visiting, done, ordered);
        }
        return ordered;
    }

    private void visit(PipelinePlugin p, Map<String, PipelinePlugin> byId,
                       Set<String> visiting, Set<String> done, List<PipelinePlugin> ordered) {
        String id = p.mod().id().toString();
        if (done.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            skipped.add(tr("Plugin dependency cycle; ignored edges: ") + id);
            return;
        }
        List<dev.luxloader.api.GpuId> deps;
        try {
            deps = p.dependencies();
        } catch (RuntimeException e) {
            errors.add(id + tr(" dependencies() threw: ") + e);
            deps = List.of();
        }
        for (dev.luxloader.api.GpuId dep : deps) {
            PipelinePlugin target = byId.get(dep.toString());
            if (target == null) {
                skipped.add(id + tr(" depends on ") + dep + tr(", which is not installed; loading anyway (features may fall back)"));
                continue;
            }
            visit(target, byId, visiting, done, ordered);
        }
        visiting.remove(id);
        done.add(id);
        ordered.add(p);
    }

    /** Load errors for diagnostics. */
    public List<String> errors() {
        return List.copyOf(errors);
    }

    /** Skipped plugins and reasons. */
    public List<String> skipped() {
        return List.copyOf(skipped);
    }

    /** Exports discovered plugins as a JSON diagnostic manifest. */
    public String exportManifest(List<PipelinePlugin> plugins) {
        List<Object> list = new ArrayList<>();
        for (PipelinePlugin p : plugins.stream().sorted(Comparator.comparing(x -> x.mod().id().toString())).toList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            LuxMod mod = p.mod();
            m.put("id", mod.id().toString());
            m.put("name", mod.name());
            m.put("version", mod.version());
            m.put("authors", mod.authors());
            m.put("class", p.getClass().getName());
            m.put("dependencies", p.dependencies().stream().map(Object::toString).toList());
            list.add(m);
        }
        return SimpleJson.writePretty(list, 2);
    }
}
