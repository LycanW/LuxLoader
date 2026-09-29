package dev.luxloader.shader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Find a test compiler through luxloader.test.slangc, LUXLOADER_SLANGC, repository .tools caches, then
 * PATH. Tests explicitly skip when no compiler is available.
 */
final class SlangTestToolchain {

    /** System property selecting the test compiler. */
    static final String PROP = "luxloader.test.slangc";

    private SlangTestToolchain() {
    }

    /** Find a usable compiler executable. */
    static Optional<Path> find() {
        String fromProperty = System.getProperty(PROP);
        if (fromProperty != null && !fromProperty.isBlank()) {
            Path p = Path.of(fromProperty);
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }

        String fromEnv = System.getenv(SlangToolchain.ENV_SLANGC);
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path p = Path.of(fromEnv);
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }

        Optional<Path> inRepo = findInTools(repoRoot());
        if (inRepo.isPresent()) {
            return inRepo;
        }

        return SlangToolchain.locate(null).map(SlangToolchain.Resolved::executable);
    }

    /** Find the repository root by searching upward for settings.gradle. */
    static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    /** Search .tools for task-managed or manually extracted compilers. */
    private static Optional<Path> findInTools(Path root) {
        Path tools = root.resolve(".tools");
        if (!Files.isDirectory(tools)) {
            return Optional.empty();
        }
        String name = SlangToolchain.executableName();

        // First inspect the fetchSlangc task's standard unpacked-version-platform/bin directory.
        Path cache = tools.resolve("slang-cache");
        if (Files.isDirectory(cache)) {
            try (Stream<Path> dirs = Files.list(cache)) {
                Optional<Path> canonical = dirs
                        .filter(Files::isDirectory)
                        .map(dir -> dir.resolve("bin").resolve(name))
                        .filter(Files::isRegularFile)
                        .filter(p -> !SlangToolchain.queryVersion(p).isEmpty())
                        .findFirst();
                if (canonical.isPresent()) {
                    return canonical;
                }
            } catch (IOException ignored) {
                // Fall back to a full scan.
            }
        }

        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(tools, 5)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(name))
                    .forEach(candidates::add);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // Prefer a complete distribution including slang-compiler dependencies.
        candidates.sort((a, b) -> Integer.compare(score(b), score(a)));
        for (Path candidate : candidates) {
            if (!SlangToolchain.queryVersion(candidate).isEmpty()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Rank candidate directories by the number of related distribution files. */
    private static int score(Path executable) {
        Path dir = executable.getParent();
        if (dir == null) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.startsWith("slang") || n.startsWith("libslang");
            }).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** A usable compiler, or null. */
    static SlangCompiler compilerOrNull(Path workRoot) {
        return find().flatMap(path -> SlangCompiler.detect(path, workRoot)).orElse(null);
    }
}
