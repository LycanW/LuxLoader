package dev.luxloader.shader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests shader cache keys and persistence, verifying which input changes invalidate artifacts and
 * which preserve reuse.
 */
class ShaderCacheTest {

    @TempDir
    Path tempDir;

    private static ShaderSource.FromSource basic(String source) {
        return ShaderSource.FromSource.of("test", ShaderStage.COMPUTE, source);
    }

    @Test
    @DisplayName("Key Is Stable")
    void keyIsStable() {
        String a = ShaderCache.keyFor(basic("// hello"), List.of(), "slangc 2026.18");
        String b = ShaderCache.keyFor(basic("// hello"), List.of(), "slangc 2026.18");

        assertEquals(a, b, "Identical inputs must produce identical cache keys");
        assertEquals(32, a.length(), "Short keys must have a fixed filename-friendly length");
        assertTrue(a.matches("[0-9a-f]{32}"), a);
    }

    @Test
    @DisplayName("Source Change Changes Key")
    void sourceChangeChangesKey() {
        assertNotEquals(
                ShaderCache.keyFor(basic("// one"), List.of(), "c"),
                ShaderCache.keyFor(basic("// two"), List.of(), "c"));
    }

    @Test
    @DisplayName("Compiler Version Changes Key")
    void compilerVersionChangesKey() {
        assertNotEquals(
                ShaderCache.keyFor(basic("// x"), List.of(), "slangc 2026.18"),
                ShaderCache.keyFor(basic("// x"), List.of(), "slangc 2026.19"));
    }

    @Test
    @DisplayName("Defines Change Key")
    void definesChangeKey() {
        ShaderSource.FromSource plain = basic("// x");
        ShaderSource.FromSource withDefine = plain.withDefine("QUALITY", "2");

        assertNotEquals(ShaderCache.keyFor(plain, List.of(), "c"),
                ShaderCache.keyFor(withDefine, List.of(), "c"));
    }

    @Test
    @DisplayName("Define Order Does Not Change Key")
    void defineOrderDoesNotChangeKey() {
        ShaderSource.FromSource a = basic("// x")
                .withDefine("A", "1").withDefine("B", "2");
        ShaderSource.FromSource b = basic("// x")
                .withDefine("B", "2").withDefine("A", "1");

        assertEquals(ShaderCache.keyFor(a, List.of(), "c"), ShaderCache.keyFor(b, List.of(), "c"));
    }

    @Test
    @DisplayName("Target Changes Key")
    void targetChangesKey() {
        assertNotEquals(
                ShaderCache.keyFor(basic("// x"), List.of(), "c"),
                ShaderCache.keyFor(basic("// x").withTarget(ShaderTarget.SPIRV_1_5), List.of(), "c"));
    }

    @Test
    @DisplayName("Entry Point Changes Key")
    void entryPointChangesKey() {
        assertNotEquals(
                ShaderCache.keyFor(basic("// x"), List.of(), "c"),
                ShaderCache.keyFor(basic("// x").withEntryPoint("cs_alt"), List.of(), "c"));
    }

    @Test
    @DisplayName("Extra Arguments Change Key")
    void extraArgumentsChangeKey() {
        ShaderSource.FromSource plain = basic("// x");
        ShaderSource.FromSource withArg = new ShaderSource.FromSource("test", ShaderStage.COMPUTE,
                "main", "// x", ShaderLanguage.SLANG, Map.of(), ShaderTarget.SPIRV_1_0,
                List.of("-fvk-use-entrypoint-name"), null);

        assertNotEquals(ShaderCache.keyFor(plain, List.of(), "c"),
                ShaderCache.keyFor(withArg, List.of(), "c"));
    }

    @Test
    @DisplayName("Include Content Changes Key")
    void includeContentChangesKey() throws IOException {
        Path includeDir = Files.createDirectories(tempDir.resolve("inc"));
        Path common = includeDir.resolve("common.h");
        Files.writeString(common, "#define A 1\n", StandardCharsets.UTF_8);

        ShaderSource.FromSource source = basic("// x");
        String before = ShaderCache.keyFor(source, List.of(includeDir), "c");

        Files.writeString(common, "#define A 2\n", StandardCharsets.UTF_8);
        String after = ShaderCache.keyFor(source, List.of(includeDir), "c");

        assertNotEquals(before, after,
                "Include changes must invalidate compiled artifacts");
    }

    @Test
    @DisplayName("New Include File Changes Key")
    void newIncludeFileChangesKey() throws IOException {
        Path includeDir = Files.createDirectories(tempDir.resolve("inc2"));
        ShaderSource.FromSource source = basic("// x");
        String before = ShaderCache.keyFor(source, List.of(includeDir), "c");

        Files.writeString(includeDir.resolve("extra.h"), "// new\n", StandardCharsets.UTF_8);
        String after = ShaderCache.keyFor(source, List.of(includeDir), "c");

        assertNotEquals(before, after);
    }

    @Test
    @DisplayName("Absolute Include Path Does Not Affect Key")
    void absoluteIncludePathDoesNotAffectKey() throws IOException {
        Path one = Files.createDirectories(tempDir.resolve("a/inc"));
        Path two = Files.createDirectories(tempDir.resolve("b/inc"));
        Files.writeString(one.resolve("x.h"), "// same\n", StandardCharsets.UTF_8);
        Files.writeString(two.resolve("x.h"), "// same\n", StandardCharsets.UTF_8);

        ShaderSource.FromSource source = basic("// x");
        assertEquals(ShaderCache.keyFor(source, List.of(one), "c"),
                ShaderCache.keyFor(source, List.of(two), "c"),
                "Identical includes in different checkouts must share cache keys");
    }

    @Test
    @DisplayName("Precompiled Key Uses Spirv Bytes")
    void precompiledKeyUsesSpirvBytes() {
        ShaderSource.Precompiled a = new ShaderSource.Precompiled("p", ShaderStage.COMPUTE, "main",
                new byte[] {1, 2, 3, 4});
        ShaderSource.Precompiled b = new ShaderSource.Precompiled("p", ShaderStage.COMPUTE, "main",
                new byte[] {1, 2, 3, 5});

        assertNotEquals(ShaderCache.keyFor(a, List.of(), "c"), ShaderCache.keyFor(b, List.of(), "c"));
        assertEquals(ShaderCache.keyFor(a, List.of(), "c"),
                ShaderCache.keyFor(new ShaderSource.Precompiled("p", ShaderStage.COMPUTE, "main",
                        new byte[] {1, 2, 3, 4}), List.of(), "c"));
    }

    @Test
    @DisplayName("Stores And Loads")
    void storesAndLoads() {
        ShaderCache cache = new ShaderCache(tempDir.resolve("cache"));
        byte[] spirv = {0x03, 0x02, 0x23, 0x07};

        assertTrue(cache.lookup("abc").isEmpty());
        assertEquals(0, cache.size());

        assertTrue(cache.store("abc", spirv, Map.of("name", "bloom", "elapsedMillis", "42")));

        assertEquals(1, cache.size());
        assertArrayEquals(spirv, cache.lookup("abc").orElseThrow());

        Map<String, String> meta = cache.metadata("abc").orElseThrow();
        assertEquals("bloom", meta.get("name"));
        assertEquals("42", meta.get("elapsedMillis"));
    }

    @Test
    @DisplayName("Sanitizes Metadata")
    void sanitizesMetadata() {
        ShaderCache cache = new ShaderCache(tempDir.resolve("cache2"));
        cache.store("k", new byte[] {1}, Map.of("compiler", "slangc\n2026.18"));

        Map<String, String> meta = cache.metadata("k").orElseThrow();
        assertEquals("slangc 2026.18", meta.get("compiler"));
    }

    @Test
    @DisplayName("Clears")
    void clears() {
        ShaderCache cache = new ShaderCache(tempDir.resolve("cache3"));
        cache.store("a", new byte[] {1}, Map.of());
        cache.store("b", new byte[] {2}, Map.of());
        assertEquals(2, cache.size());

        assertTrue(cache.clear() >= 2);
        assertEquals(0, cache.size());
        assertTrue(cache.lookup("a").isEmpty());
    }

    @Test
    @DisplayName("Missing Directory Is Harmless")
    void missingDirectoryIsHarmless() {
        ShaderCache cache = new ShaderCache(tempDir.resolve("never-created"));

        assertTrue(cache.lookup("x").isEmpty());
        assertEquals(0, cache.size());
        assertEquals(0, cache.clear());
        assertTrue(cache.metadata("x").isEmpty());
    }

    @Test
    @DisplayName("Missing Include Directory Is Tolerated")
    void missingIncludeDirectoryIsTolerated() {
        ShaderSource.FromSource source = basic("// x");
        assertFalse(ShaderCache.keyFor(source, List.of(tempDir.resolve("nope")), "c").isEmpty());
        assertEquals(ShaderCache.keyFor(source, List.of(), "c"),
                ShaderCache.keyFor(source, List.of(tempDir.resolve("nope")), "c"),
                "Missing directories must not affect cache keys");
    }

    @Test
    @DisplayName("File Backed Source Hashes Content")
    void fileBackedSourceHashesContent() throws IOException {
        Path one = Files.createDirectories(tempDir.resolve("p1")).resolve("s.slang");
        Path two = Files.createDirectories(tempDir.resolve("p2")).resolve("s.slang");
        Files.writeString(one, "// same content", StandardCharsets.UTF_8);
        Files.writeString(two, "// same content", StandardCharsets.UTF_8);

        ShaderSource.FromSource a = ShaderSource.FromSource.fromFile(one, ShaderStage.COMPUTE, "main");
        ShaderSource.FromSource b = ShaderSource.FromSource.fromFile(two, ShaderStage.COMPUTE, "main");

        assertEquals(ShaderCache.keyFor(a, List.of(), "c"), ShaderCache.keyFor(b, List.of(), "c"));
    }
}
