package dev.luxloader.mc;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared client JAR discovery for tests, preventing different suites from inconsistently locating or
 * skipping the same installation.
 */
final class MinecraftClientJar {

    private MinecraftClientJar() {
    }

    /**
     * Create a client class loader including .minecraft/libraries. Member signatures reference external
     * libraries such as Brigadier; without them, reflective member enumeration can fail even for unrelated
     * fields. Locate libraries relative to versions/<version>/<jar>, falling back to the JAR alone when
     * unavailable.
     */
    static URLClassLoader classLoaderFor(Path clientJar) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(clientJar.toUri().toURL());
        Path libraries = librariesDir(clientJar);
        if (libraries != null) {
            try (var stream = Files.walk(libraries)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(p -> !p.getFileName().toString().contains("-sources"))
                        .forEach(p -> {
                            try {
                                urls.add(p.toUri().toURL());
                            } catch (Exception ignored) {
                                // One malformed path must not prevent constructing the class loader.
                            }
                        });
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]),
                MinecraftClientJar.class.getClassLoader());
    }

    /** Find .minecraft/libraries relative to versions/<version>/<jar>, or return null. */
    static Path librariesDir(Path clientJar) {
        Path dir = clientJar.toAbsolutePath().getParent();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("libraries");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Resolve the client JAR from system property, environment variable, then automatic discovery; return
     * null if absent.
     */
    static Path resolve() {
        String fromProperty = System.getProperty("luxloader.test.minecraftJar", "");
        if (!fromProperty.isBlank()) {
            Path p = Path.of(fromProperty);
            return Files.isRegularFile(p) ? p : null;
        }
        String fromEnv = System.getenv("LUXLOADER_MINECRAFT_JAR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path p = Path.of(fromEnv);
            return Files.isRegularFile(p) ? p : null;
        }
        return autoDetect();
    }

    /** Search common Minecraft directories for a 26.3 client JAR. */
    static Path autoDetect() {
        List<Path> roots = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            roots.add(Path.of(appData, ".minecraft", "versions"));
        }
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            roots.add(Path.of(home, ".minecraft", "versions"));
            roots.add(Path.of(home, "Library", "Application Support", "minecraft", "versions"));
        }

        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.list(root)) {
                Optional<Path> found = stream
                        .filter(Files::isDirectory)
                        .filter(dir -> dir.getFileName().toString().contains("26.3"))
                        .flatMap(dir -> {
                            try {
                                return Files.list(dir)
                                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                                        .filter(p -> !p.getFileName().toString().contains("natives"));
                            } catch (Exception e) {
                                return java.util.stream.Stream.<Path>empty();
                            }
                        })
                        .filter(p -> {
                            try {
                                // Exclude small library JARs using the client's size threshold of 30 MB.
                                return Files.size(p) > 10L * 1024 * 1024;
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (Exception ignored) {
                // Try the next installation root.
            }
        }
        return null;
    }
}
