package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Locates and extracts slangc. Bundled executables must live on disk; reuse extraction under
 * platform/version directories using a small completion manifest. Lookup order is explicit
 * configuration/environment, bundled toolchain, then PATH, preventing incidental system versions from
 * overriding the bundle. Windows SPIR-V compilation needs slangc.exe, slang.dll, slang-compiler.dll
 * and slang-glslang.dll, including downstream spirv-opt support. CPU-only slang-llvm is not bundled.
 */
public final class SlangToolchain {

    /** Environment variable specifying the compiler executable. */
    public static final String ENV_SLANGC = "LUXLOADER_SLANGC";

    /** Equivalent system property for test injection. */
    public static final String PROP_SLANGC = "luxloader.slangc";

    /** Resource root. */
    private static final String RESOURCE_ROOT = "/native/";

    /** Compiler files by platform in extraction order, executable first. */
    private static final java.util.Map<String, List<String>> PLATFORM_FILES = java.util.Map.of(
            "windows-x86_64", List.of("slangc.exe", "slang.dll", "slang-compiler.dll",
                    "slang-glslang.dll"),
            "windows-aarch64", List.of("slangc.exe", "slang.dll", "slang-compiler.dll",
                    "slang-glslang.dll"),
            "linux-x86_64", List.of("slangc", "libslang.so", "libslang-compiler.so",
                    "libslang-glslang.so"),
            "linux-aarch64", List.of("slangc", "libslang.so", "libslang-compiler.so",
                    "libslang-glslang.so"),
            "macos-x86_64", List.of("slangc", "libslang.dylib", "libslang-compiler.dylib",
                    "libslang-glslang.dylib"),
            "macos-aarch64", List.of("slangc", "libslang.dylib", "libslang-compiler.dylib",
                    "libslang-glslang.dylib"));

    /** Executable detection by platform filename conventions. */
    private static final Set<String> NON_EXECUTABLE_SUFFIXES = Set.of(".dll", ".dylib", ".so");

    private SlangToolchain() {
    }

    /**
     * Resolved compiler.
     * @param executable executable path
     * @param version slangc -v result, empty when unknown
     * @param origin toolchain source
     * @param libraryDir optional directory for dynamic-library lookup
     */
    public record Resolved(Path executable, String version, Origin origin, Path libraryDir) {

        /** Toolchain origin. */
        public enum Origin {
            /** Explicit user path. */
            EXPLICIT("Explicit path"),
            /** Bundled JAR toolchain. */
            BUNDLED("Bundled in JAR"),
            /** Discovered on PATH. */
            PATH("System PATH");

            private final String displayName;

            Origin(String displayName) {
                this.displayName = displayName;
            }

            /** Localized label, resolved when displayed. */
            public String displayName() { return tr(displayName); }

            /** Compatibility alias for existing plugin callers. */
            @Deprecated
            public String chinese() { return displayName(); }
        }

        /** Versioned display description for logs; cache identity is separate and language-independent. */
        public String describe() {
            String v = version.isEmpty() ? tr("Unknown version") : version;
            return "slangc " + v + "（" + origin.displayName() + "）";
        }

        /** Executable directory. */
        public Path directory() {
            Path parent = executable.getParent();
            return parent == null ? Path.of(".").toAbsolutePath() : parent;
        }
    }

    /** Platform identifier, e.g. windows-x86_64. */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return osName(os) + "-" + archName(arch);
    }

    private static String osName(String os) {
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        return "linux";
    }

    private static String archName(String arch) {
        return switch (arch) {
            case "aarch64", "arm64" -> "aarch64";
            case "x86_64", "amd64", "x64" -> "x86_64";
            default -> arch;
        };
    }

    /** Compiler executable filename. */
    public static String executableName() {
        return executableName(platform());
    }

    static String executableName(String platform) {
        return platform.startsWith("windows") ? "slangc.exe" : "slangc";
    }

    /**
     * Locates a compiler in the configured order.
     * @param explicitPath optional explicit path
     * @param cacheRoot extraction cache, null for default
     * @return empty if no compiler is available
     */
    public static Optional<Resolved> locate(Path explicitPath, Path cacheRoot) {
        Optional<Resolved> explicit = explicitPath != null
                ? probe(explicitPath, Resolved.Origin.EXPLICIT)
                : Optional.empty();
        if (explicit.isPresent()) {
            return explicit;
        }

        String fromEnv = System.getProperty(PROP_SLANGC);
        if (fromEnv == null || fromEnv.isBlank()) {
            fromEnv = System.getenv(ENV_SLANGC);
        }
        if (fromEnv != null && !fromEnv.isBlank()) {
            Optional<Resolved> fromProperty = probe(Path.of(fromEnv), Resolved.Origin.EXPLICIT);
            if (fromProperty.isPresent()) {
                return fromProperty;
            }
        }

        Optional<Resolved> bundled = extractBundled(cacheRoot == null ? defaultCacheRoot() : cacheRoot);
        if (bundled.isPresent()) {
            return bundled;
        }

        return fromPath();
    }

    /** Lookup order: explicit path, environment/property, bundled toolchain, then PATH. */
    public static Optional<Resolved> locate(Path explicitPath) {
        return locate(explicitPath, null);
    }

    /** Default cache root: ~/.luxloader. */
    public static Path defaultCacheRoot() {
        String override = System.getProperty("luxloader.cacheDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home", "."), ".luxloader");
    }

    /** Probes a candidate compiler file. */
    private static Optional<Resolved> probe(Path candidate, Resolved.Origin origin) {
        if (candidate == null) {
            return Optional.empty();
        }
        Path exe = candidate;
        if (Files.isDirectory(candidate)) {
            exe = candidate.resolve(executableName());
        }
        if (!Files.isRegularFile(exe)) {
            return Optional.empty();
        }
        String version = queryVersion(exe);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(exe.toAbsolutePath(), version, origin, exe.toAbsolutePath().getParent()));
    }

    /** Searches PATH. */
    private static Optional<Resolved> fromPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String name = executableName();
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir).resolve(name);
                if (Files.isRegularFile(candidate)) {
                    String version = queryVersion(candidate);
                    if (!version.isEmpty()) {
                        return Optional.of(new Resolved(candidate.toAbsolutePath(), version,
                                Resolved.Origin.PATH, candidate.toAbsolutePath().getParent()));
                    }
                }
            } catch (RuntimeException ignored) {
                // Skip invalid PATH entries.
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts the bundled compiler only when the versioned directory is incomplete or mismatched.
     * Versioned directories avoid reusing an old compiler after upgrades.
     */
    static Optional<Resolved> extractBundled(Path cacheRoot) {
        return extractBundled(cacheRoot, platform(), SlangToolchain.class);
    }

    /**
     * Extraction implementation with injectable platform/anchor for small test fixtures covering
     * extraction, executable permissions, manifests and version changes.
     * @param cacheRoot extraction root
     * @param platform resource subdirectory identifier
     * @param anchor resource lookup class, normally SlangToolchain
     */
    static Optional<Resolved> extractBundled(Path cacheRoot, String platform, Class<?> anchor) {
        String resourceDir = RESOURCE_ROOT + platform + "/";
        String executable = executableName(platform);
        try (InputStream probeStream = anchor.getResourceAsStream(resourceDir + executable)) {
            if (probeStream == null) {
                return Optional.empty();
            }
        } catch (IOException e) {
            return Optional.empty();
        }

        Properties manifest = readManifest(anchor, resourceDir, platform);
        String version = manifest.getProperty("slang.version", "unknown");
        Path targetDir = cacheRoot.resolve("tools").resolve("slang")
                .resolve(platform + "-" + version);

        Path exe = targetDir.resolve(executable);
        if (!isUpToDate(targetDir, manifest, platform)) {
            try {
                extract(anchor, resourceDir, targetDir, manifest, platform);
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        if (!Files.isRegularFile(exe)) {
            return Optional.empty();
        }
        String probed = queryVersion(exe);
        return Optional.of(new Resolved(exe.toAbsolutePath(),
                probed.isEmpty() ? version : probed, Resolved.Origin.BUNDLED, targetDir));
    }

    /** Reads the bundled manifest or synthesizes one from known filenames. */
    private static Properties readManifest(Class<?> anchor, String resourceDir, String platform) {
        Properties props = new Properties();
        try (InputStream in = anchor.getResourceAsStream(resourceDir + "manifest.properties")) {
            if (in != null) {
                props.load(in);
                return props;
            }
        } catch (IOException ignored) {
            // Fall back to known filenames if the manifest is corrupt.
        }
        List<String> files = PLATFORM_FILES.getOrDefault(platform, List.of());
        props.setProperty("slang.version", "unknown");
        props.setProperty("files", String.join(",", files));
        return props;
    }

    /** Whether extraction is complete and matches the version. */
    private static boolean isUpToDate(Path targetDir, Properties manifest, String platform) {
        Path copied = targetDir.resolve("manifest.properties");
        if (!Files.isRegularFile(copied)) {
            return false;
        }
        Properties existing = new Properties();
        try (InputStream in = Files.newInputStream(copied)) {
            existing.load(in);
        } catch (IOException e) {
            return false;
        }
        String wanted = manifest.getProperty("slang.version", "unknown");
        String have = existing.getProperty("slang.version", "unknown");
        if (!wanted.equals(have) || "unknown".equals(wanted)) {
            return false;
        }
        for (String name : fileNames(manifest, platform)) {
            if (!Files.isRegularFile(targetDir.resolve(name))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> fileNames(Properties manifest, String platform) {
        String raw = manifest.getProperty("files", "");
        List<String> names = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                names.add(t);
            }
        }
        if (names.isEmpty()) {
            names.addAll(PLATFORM_FILES.getOrDefault(platform, List.of()));
        }
        return names;
    }

    private static void extract(Class<?> anchor, String resourceDir, Path targetDir,
                                Properties manifest, String platform) throws IOException {
        Files.createDirectories(targetDir);
        List<String> names = fileNames(manifest, platform);
        for (String name : names) {
            String resource = resourceDir + name;
            try (InputStream in = anchor.getResourceAsStream(resource)) {
                if (in == null) {
                    continue;
                }
                Path out = targetDir.resolve(name);
                Path tmp = targetDir.resolve(name + ".part");
                try (OutputStream os = Files.newOutputStream(tmp)) {
                    in.transferTo(os);
                }
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
                if (isExecutableName(name)) {
                    makeExecutable(out);
                }
            }
        }
        // Write the completion manifest last so interrupted extraction is retried.
        try (OutputStream os = Files.newOutputStream(targetDir.resolve("manifest.properties"))) {
            manifest.store(os, "LuxLoader bundled Slang toolchain (" + platform + ")");
        }
    }

    private static boolean isExecutableName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : NON_EXECUTABLE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return false;
            }
        }
        return true;
    }

    private static void makeExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = new java.util.HashSet<>(
                    Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows uses executable suffixes instead of POSIX permission bits.
        }
        file.toFile().setExecutable(true, false);
    }

    /**
     * Queries slangc -v, normally a single version line. Empty means the candidate could not be verified
     * and must not be treated as usable.
     */
    static String queryVersion(Path executable) {
        try {
            ProcessBuilder pb = new ProcessBuilder(executable.toString(), "-v");
            pb.redirectErrorStream(true);
            Path dir = executable.toAbsolutePath().getParent();
            if (dir != null) {
                pb.directory(dir.toFile());
                pb.environment().putAll(libraryPathEnv(dir));
            }
            Process p = pb.start();
            String output;
            try (InputStream in = p.getInputStream()) {
                output = new String(in.readAllBytes());
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            if (p.exitValue() != 0) {
                return "";
            }
            for (String line : output.split("\\R")) {
                String t = line.strip();
                if (!t.isEmpty()) {
                    return t;
                }
            }
            return "";
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /**
     * Builds child-process library paths. Windows searches beside the executable; add that directory to
     * LD_LIBRARY_PATH/DYLD_LIBRARY_PATH on Linux/macOS for compiler dependencies.
     */
    static java.util.Map<String, String> libraryPathEnv(Path libraryDir) {
        Objects.requireNonNull(libraryDir, "libraryDir");
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        String dir = libraryDir.toAbsolutePath().toString();
        String platform = platform();
        if (platform.startsWith("linux")) {
            env.put("LD_LIBRARY_PATH", prepend(dir, System.getenv("LD_LIBRARY_PATH")));
        } else if (platform.startsWith("macos")) {
            env.put("DYLD_LIBRARY_PATH", prepend(dir, System.getenv("DYLD_LIBRARY_PATH")));
        }
        return env;
    }

    private static String prepend(String dir, String existing) {
        return existing == null || existing.isBlank() ? dir : dir + java.io.File.pathSeparator + existing;
    }
}
