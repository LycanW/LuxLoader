package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Disk compilation cache keyed by SHA-256 of compiler identity/version, target, stage, entry point,
 * language, defines, arguments, source and include contents. Include changes must invalidate unchanged
 * root sources. Include scanning stops at MAX_HASHED_FILES or 8 MiB to bound startup cost when a large
 * resource directory is supplied; only the scanned portion participates beyond that limit.
 */
public final class ShaderCache {

    /** Maximum included files participating in hashing. */
    public static final int MAX_HASHED_FILES = 512;

    /** Maximum included bytes participating in hashing. */
    public static final long MAX_HASHED_BYTES = 8L * 1024 * 1024;

    /** Cache format version; increment after semantic changes to invalidate old entries. */
    public static final String FORMAT_VERSION = "luxloader-shader-1";

    private final Path root;

    public ShaderCache(Path root) {
        this.root = root;
    }

    /** Cache root directory. */
    public Path root() {
        return root;
    }

    /** Looks up compiled output by key. */
    public Optional<byte[]> lookup(String key) {
        Path file = pathFor(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Stores output; write failures are nonfatal because caching is an optimization. */
    public boolean store(String key, byte[] spirv, Map<String, String> metadata) {
        try {
            Files.createDirectories(root);
            Path target = pathFor(key);
            Path tmp = root.resolve(key + ".spv.part");
            Files.write(tmp, spirv);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            if (metadata != null && !metadata.isEmpty()) {
                Map<String, String> sorted = new TreeMap<>(metadata);
                StringBuilder sb = new StringBuilder();
                sorted.forEach((k, v) -> sb.append(k).append('=')
                        .append(v == null ? "" : v.replace('\n', ' ')).append('\n'));
                Files.writeString(metaPathFor(key), sb.toString(), StandardCharsets.UTF_8);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Reads entry metadata. */
    public Optional<Map<String, String>> metadata(String key) {
        Path file = metaPathFor(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Map<String, String> map = new TreeMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    map.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            return Optional.of(map);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Current cache entry count. */
    public int size() {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(root)) {
            return (int) files.filter(p -> p.getFileName().toString().endsWith(".spv")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Clears entries and returns the number deleted. */
    public int clear() {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> files = Files.list(root)) {
            for (Path p : files.toList()) {
                try {
                    if (Files.deleteIfExists(p)) {
                        removed++;
                    }
                } catch (IOException ignored) {
                    // Leave locked files in place.
                }
            }
        } catch (IOException ignored) {
            // Unreadable directories count as no deletions.
        }
        return removed;
    }

    private Path pathFor(String key) {
        return root.resolve(key + ".spv");
    }

    private Path metaPathFor(String key) {
        return root.resolve(key + ".meta");
    }

    // Cache key construction.

    /**
     * Computes a shader cache key.
     * @param source shader source
     * @param includes include directories
     * @param compiler stable versioned compiler identity
     * @return 32-character hexadecimal key
     */
    public static String keyFor(ShaderSource source, List<Path> includes, String compiler) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(FORMAT_VERSION).append('\n');
        canonical.append("compiler=").append(compiler == null ? "" : compiler).append('\n');
        canonical.append("stage=").append(source.stage().key()).append('\n');
        canonical.append("entry=").append(source.entryPoint()).append('\n');

        if (source instanceof ShaderSource.FromSource fromSource) {
            canonical.append("language=").append(fromSource.language().key()).append('\n');
            canonical.append("target=").append(fromSource.target().profile())
                    .append('/').append(fromSource.target().kind().slangName()).append('\n');
            new TreeMap<>(fromSource.defines())
                    .forEach((k, v) -> canonical.append("define=").append(k).append('=')
                            .append(v == null ? "" : v).append('\n'));
            for (String arg : fromSource.extraArguments()) {
                canonical.append("arg=").append(arg).append('\n');
            }
            try {
                canonical.append("source=")
                        .append(sha256(fromSource.readSource().getBytes(StandardCharsets.UTF_8)))
                        .append('\n');
            } catch (IOException e) {
                // Include the source path when reading fails so unrelated failed sources do not share a key.
                canonical.append("source-unreadable=")
                        .append(fromSource.origin() == null ? "?" : fromSource.origin())
                        .append('\n');
            }
            appendIncludes(canonical, includes, fromSource.sourceDirectory().orElse(null));
        } else {
            ShaderSource.Precompiled precompiled = (ShaderSource.Precompiled) source;
            canonical.append("spirv=").append(sha256(precompiled.spirv())).append('\n');
        }

        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    /**
     * Hashes relative include paths, sizes and contents without absolute checkout paths, permitting reuse
     * across machines.
     */
    private static void appendIncludes(StringBuilder canonical, List<Path> includes,
                                       Path sourceDir) {
        List<Path> dirs = new ArrayList<>();
        if (includes != null) {
            for (Path p : includes) {
                if (p != null && Files.isDirectory(p)) {
                    dirs.add(p.toAbsolutePath().normalize());
                }
            }
        }
        if (sourceDir != null && Files.isDirectory(sourceDir)) {
            Path normalized = sourceDir.toAbsolutePath().normalize();
            if (!dirs.contains(normalized)) {
                dirs.add(normalized);
            }
        }
        dirs.sort(Comparator.comparing(Path::toString));

        int files = 0;
        long bytes = 0;
        for (Path dir : dirs) {
            List<Path> found = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(found::add);
            } catch (IOException e) {
                canonical.append("include-dir-unreadable=").append(dir.getFileName()).append('\n');
                continue;
            }
            found.sort(Comparator.comparing(p -> dir.relativize(p).toString()));
            for (Path file : found) {
                if (files >= MAX_HASHED_FILES || bytes >= MAX_HASHED_BYTES) {
                    canonical.append("include-truncated=true\n");
                    return;
                }
                try {
                    long size = Files.size(file);
                    canonical.append("include=").append(dir.relativize(file))
                            .append('|').append(size)
                            .append('|').append(sha256File(file))
                            .append('\n');
                    files++;
                    bytes += size;
                } catch (IOException e) {
                    canonical.append("include-unreadable=").append(dir.relativize(file)).append('\n');
                }
            }
        }
    }

    // Hashing.

    private static String sha256(byte[] data) {
        return HexFormat.of().formatHex(newDigest().digest(data)).toLowerCase(Locale.ROOT);
    }

    private static String sha256File(Path file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JDK; absence indicates an incomplete runtime.
            throw new IllegalStateException(tr("SHA-256 unavailable in this runtime"), e);
        }
    }

    @Override
    public String toString() {
        return "ShaderCache{" + root + ", " + size() + tr(" entries}");
    }
}
