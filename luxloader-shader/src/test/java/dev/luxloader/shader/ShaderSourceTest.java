package dev.luxloader.shader;

import dev.luxloader.api.vulkan.ComputeFillModule;
import dev.luxloader.api.vulkan.SpirvGen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests stage aliases, target inference, source descriptions, defensive copies and precompiled SPIR-V
 * validation.
 */
class ShaderSourceTest {

    @Nested
    @DisplayName("Stages")
    class Stages {

        @Test
        @DisplayName("Parses Both Naming Styles")
        void parsesBothNamingStyles() {
            assertEquals(ShaderStage.COMPUTE, ShaderStage.parse("COMPUTE"));
            assertEquals(ShaderStage.COMPUTE, ShaderStage.parse("compute"));
            assertEquals(ShaderStage.RAY_GENERATION, ShaderStage.parse("raygeneration"),
                    "Recognize both Slang and enum names for RT stages");
            assertEquals(ShaderStage.RAY_GENERATION, ShaderStage.parse("RAY_GENERATION"));
            assertEquals(ShaderStage.FRAGMENT, ShaderStage.parse("fragment"));
            assertEquals(ShaderStage.AMPLIFICATION, ShaderStage.parse("amplification"));
        }

        @Test
        @DisplayName("Parses Aliases")
        void parsesAliases() {
            assertEquals(ShaderStage.VERTEX, ShaderStage.parse("vert"));
            assertEquals(ShaderStage.FRAGMENT, ShaderStage.parse("frag"));
            assertEquals(ShaderStage.FRAGMENT, ShaderStage.parse("pixel"));
            assertEquals(ShaderStage.COMPUTE, ShaderStage.parse("comp"));
            assertEquals(ShaderStage.RAY_GENERATION, ShaderStage.parse("rgen"));
            assertEquals(ShaderStage.CLOSEST_HIT, ShaderStage.parse("rchit"));
            assertEquals(ShaderStage.ANY_HIT, ShaderStage.parse("rahit"));
            assertEquals(ShaderStage.AMPLIFICATION, ShaderStage.parse("task"));
        }

        @Test
        @DisplayName("Rejects Unknown Stages")
        void rejectsUnknownStages() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ShaderStage.parse("warpdrive"));
            assertTrue(e.getMessage().contains("warpdrive"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> ShaderStage.parse(""));
            assertThrows(IllegalArgumentException.class, () -> ShaderStage.parse(null));
        }

        @Test
        @DisplayName("Infers From Extension")
        void infersFromExtension() {
            assertEquals(ShaderStage.COMPUTE, ShaderStage.fromExtension("bloom.comp"));
            assertEquals(ShaderStage.FRAGMENT, ShaderStage.fromExtension("post.frag"));
            assertEquals(ShaderStage.RAY_GENERATION, ShaderStage.fromExtension("gi.rgen"));
            assertEquals(null, ShaderStage.fromExtension("thing.slang"),
                    "The slang suffix does not imply a stage; require an explicit stage");
            assertEquals(null, ShaderStage.fromExtension("noextension"));
            assertEquals(null, ShaderStage.fromExtension(null));
        }

        @Test
        @DisplayName("Identifies Ray Tracing")
        void identifiesRayTracing() {
            assertTrue(ShaderStage.RAY_GENERATION.isRayTracing());
            assertTrue(ShaderStage.MISS.isRayTracing());
            assertFalse(ShaderStage.COMPUTE.isRayTracing());
            assertFalse(ShaderStage.FRAGMENT.isRayTracing());
        }
    }

    @Nested
    @DisplayName("Targets")
    class Targets {

        @Test
        @DisplayName("Maps Vulkan To Spirv")
        void mapsVulkanToSpirv() {
            assertEquals("spirv_1_0", ShaderTarget.forVulkan(1, 0).profile());
            assertEquals("spirv_1_3", ShaderTarget.forVulkan(1, 1).profile());
            assertEquals("spirv_1_5", ShaderTarget.forVulkan(1, 2).profile());
            assertEquals("spirv_1_6", ShaderTarget.forVulkan(1, 3).profile());
            assertEquals("spirv_1_6", ShaderTarget.forVulkan(1, 4).profile());
        }

        @Test
        @DisplayName("Only Binary Is Feedable")
        void onlyBinaryIsFeedable() {
            assertTrue(ShaderTarget.SPIRV_1_5.isBinarySpirv());
            assertFalse(ShaderTarget.SPIRV_1_5.withKind(ShaderTarget.Kind.SPIRV_ASSEMBLY)
                    .isBinarySpirv());
            assertEquals("spirv_1_5", ShaderTarget.SPIRV_1_5.withKind(ShaderTarget.Kind.GLSL)
                    .profile(), "Changing output format must preserve the version");
        }

        @Test
        @DisplayName("Parses Profiles")
        void parsesProfiles() {
            assertEquals(ShaderTarget.SPIRV_1_5, ShaderTarget.parseProfile("spirv_1_5"));
            assertEquals("spirv_1_5", ShaderTarget.parseProfile("SPIRV_1_5").profile());
            assertThrows(IllegalArgumentException.class, () -> ShaderTarget.spirv(0, 5));
            assertThrows(IllegalArgumentException.class,
                    () -> new ShaderTarget("  ", ShaderTarget.Kind.SPIRV));
        }
    }

    @Nested
    @DisplayName("Languages")
    class Languages {

        @Test
        @DisplayName("Recognises Glsl Extensions")
        void recognisesGlslExtensions() {
            assertEquals(ShaderLanguage.GLSL, ShaderLanguage.fromExtension("a.comp"));
            assertEquals(ShaderLanguage.GLSL, ShaderLanguage.fromExtension("a.vert"));
            assertEquals(ShaderLanguage.GLSL, ShaderLanguage.fromExtension("a.frag"));
            assertEquals(ShaderLanguage.GLSL, ShaderLanguage.fromExtension("a.rgen"));
            assertEquals(ShaderLanguage.SLANG, ShaderLanguage.fromExtension("a.slang"));
            assertEquals(ShaderLanguage.HLSL, ShaderLanguage.fromExtension("a.hlsl"));
            assertEquals(ShaderLanguage.SLANG, ShaderLanguage.fromExtension("unknown.xyz"));
            assertEquals(ShaderLanguage.SLANG, ShaderLanguage.fromExtension(null));
        }

        @Test
        @DisplayName("Parses Names")
        void parsesNames() {
            assertEquals(ShaderLanguage.GLSL, ShaderLanguage.parse("glsl"));
            assertEquals(ShaderLanguage.HLSL, ShaderLanguage.parse("HLSL"));
            assertEquals(ShaderLanguage.SLANG, ShaderLanguage.parse(null));
            assertThrows(IllegalArgumentException.class, () -> ShaderLanguage.parse("cobol"));
        }
    }

    @Nested
    @DisplayName("Sources")
    class Sources {

        @Test
        @DisplayName("Describes Both Paths")
        void describesBothPaths() {
            ShaderSource fromSource = ShaderSource.FromSource.of("bloom", ShaderStage.COMPUTE, "// x");
            ShaderSource precompiled = new ShaderSource.Precompiled("ready", ShaderStage.COMPUTE,
                    "main", ComputeFillModule.bytes());

            assertTrue(fromSource.needsCompiler(), "Source compilation requires a compiler");
            assertFalse(precompiled.needsCompiler(), "Precompiled modules must not require a compiler");
            assertTrue(fromSource.describe().contains("slang"), fromSource.describe());
            assertTrue(precompiled.describe().contains("已编译"), precompiled.describe());
        }

        @Test
        @DisplayName("Precompiled Copies Bytes")
        void precompiledCopiesBytes() {
            byte[] original = ComputeFillModule.bytes();
            ShaderSource.Precompiled source = new ShaderSource.Precompiled("s", ShaderStage.COMPUTE,
                    "main", original);

            original[0] = 0x7F;

            assertEquals(SpirvGen.MAGIC, java.nio.ByteBuffer.wrap(source.spirv())
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(),
                    "External mutation must not affect the constructed shader");

            byte[] fetched = source.spirv();
            fetched[0] = 0x7F;
            assertNotEquals(0x7F, source.spirv()[0], "Mutating a returned copy must not alter internal state");
        }

        @Test
        @DisplayName("Requires Source Or File")
        void requiresSourceOrFile() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ShaderSource.FromSource("x", ShaderStage.COMPUTE, "main", null,
                            ShaderLanguage.SLANG, Map.of(), ShaderTarget.SPIRV_1_0, List.of(), null));
        }

        @Test
        @DisplayName("Applies Defaults")
        void appliesDefaults() {
            ShaderSource.FromSource source = new ShaderSource.FromSource("x", ShaderStage.COMPUTE,
                    "  ", "// x", null, null, null, null, null);

            assertEquals("main", source.entryPoint());
            assertEquals(ShaderTarget.SPIRV_1_0, source.target());
            assertEquals(ShaderLanguage.SLANG, source.language());
            assertTrue(source.defines().isEmpty());
            assertTrue(source.extraArguments().isEmpty());
        }

        @Test
        @DisplayName("Copy Methods Are Immutable")
        void copyMethodsAreImmutable() {
            ShaderSource.FromSource base = ShaderSource.FromSource.of("x", ShaderStage.COMPUTE, "// x");

            assertEquals("cs_alt", base.withEntryPoint("cs_alt").entryPoint());
            assertEquals("main", base.entryPoint(), "The original object must remain unchanged");
            assertTrue(base.withDefine("K", "V").defines().containsKey("K"));
            assertTrue(base.defines().isEmpty(), "The original object must remain unchanged");
            assertEquals("spirv_1_5", base.withTarget(ShaderTarget.SPIRV_1_5).target().profile());
            assertEquals("spirv_1_0", base.target().profile());
        }

        @Test
        @DisplayName("Copies Defines")
        void copiesDefines() {
            Map<String, String> mutable = new java.util.HashMap<>();
            mutable.put("A", "1");
            ShaderSource.FromSource source = new ShaderSource.FromSource("x", ShaderStage.COMPUTE,
                    "main", "// x", ShaderLanguage.SLANG, mutable, ShaderTarget.SPIRV_1_0,
                    List.of(), null);

            mutable.put("B", "2");

            assertFalse(source.defines().containsKey("B"));
            assertThrows(UnsupportedOperationException.class, () -> source.defines().put("C", "3"));
        }

        @Test
        @DisplayName("Builds From File")
        void buildsFromFile(@TempDir Path tempDir) throws IOException {
            Path file = Files.writeString(tempDir.resolve("bloom.comp"), "#version 450\n");

            ShaderSource.FromSource source = ShaderSource.FromSource.fromFile(file, null, null);

            assertEquals(ShaderStage.COMPUTE, source.stage());
            assertEquals(ShaderLanguage.GLSL, source.language());
            assertEquals("main", source.entryPoint());
            assertTrue(source.sourceDirectory().isPresent());
            assertEquals(tempDir, source.sourceDirectory().orElseThrow());
            assertEquals("#version 450\n", source.readSource());
        }

        @Test
        @DisplayName("Rejects Ambiguous File Stage")
        void rejectsAmbiguousFileStage(@TempDir Path tempDir) throws IOException {
            Path file = Files.writeString(tempDir.resolve("thing.slang"), "// x");

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ShaderSource.FromSource.fromFile(file, null, "main"));
            assertTrue(e.getMessage().contains("thing.slang"), e.getMessage());
        }

        @Test
        @DisplayName("No Source Directory For In Memory Source")
        void noSourceDirectoryForInMemorySource() {
            assertTrue(ShaderSource.FromSource.of("x", ShaderStage.COMPUTE, "// x")
                    .sourceDirectory().isEmpty());
        }
    }

    @Nested
    @DisplayName("Precompiled")
    class Precompiled {

        @Test
        @DisplayName("Accepts Valid Module")
        void acceptsValidModule() {
            ShaderSource.Precompiled source = new ShaderSource.Precompiled("ok", ShaderStage.COMPUTE,
                    "main", ComputeFillModule.bytes());

            ShaderCompileResult result = PrecompiledCompiler.INSTANCE.compile(source, List.of());

            assertTrue(result.success(), () -> result.report());
            assertArrayEquals(ComputeFillModule.bytes(), result.spirv());
            assertTrue(PrecompiledCompiler.INSTANCE.available(),
                    "Precompiled modules require no external program and must remain available");
        }

        @Test
        @DisplayName("Rejects Truncated Module")
        void rejectsTruncatedModule() {
            byte[] full = ComputeFillModule.bytes();
            byte[] truncated = Arrays.copyOf(full, full.length - 3);

            ShaderSource.Precompiled source = new ShaderSource.Precompiled("bad", ShaderStage.COMPUTE,
                    "main", truncated);
            ShaderCompileResult result = PrecompiledCompiler.INSTANCE.compile(source, List.of());

            assertFalse(result.success());
            assertTrue(result.report().contains("结构"), result.report());
            assertTrue(result.report().contains("崩溃"), "Explain why the loader rejects the module: " + result.report());
            assertEquals("SPIR-V", result.diagnostics().get(0).code());
        }

        @Test
        @DisplayName("Rejects Garbage")
        void rejectsGarbage() {
            assertFalse(PrecompiledCompiler.INSTANCE.compile(new ShaderSource.Precompiled(
                    "empty", ShaderStage.COMPUTE, "main", new byte[0]), List.of()).success());

            assertFalse(PrecompiledCompiler.INSTANCE.compile(new ShaderSource.Precompiled(
                    "garbage", ShaderStage.COMPUTE, "main", new byte[] {1, 2, 3, 4}), List.of())
                    .success());
        }

        @Test
        @DisplayName("Rejects Source Input")
        void rejectsSourceInput() {
            ShaderCompileResult result = PrecompiledCompiler.INSTANCE.compile(
                    ShaderSource.FromSource.of("x", ShaderStage.COMPUTE, "// x"), List.of());

            assertFalse(result.success());
            assertTrue(result.error().contains("SlangCompiler"), result.error());
        }

        @Test
        @DisplayName("Loads From Classpath")
        void loadsFromClasspath() {
            IOException e = assertThrows(IOException.class, () -> ShaderSource.Precompiled
                    .fromClasspath("/definitely/not/here.comp.spv", ShaderStage.COMPUTE, "main"));
            assertTrue(e.getMessage().contains("classpath"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("Results")
    class Results {

        @Test
        @DisplayName("Copies Spirv")
        void copiesSpirv() {
            byte[] spirv = ComputeFillModule.bytes();
            ShaderCompileResult result = ShaderCompileResult.ok("x", ShaderStage.COMPUTE, "main",
                    spirv, List.of(), 5L, false, "c");

            spirv[0] = 0x7F;

            assertEquals(SpirvGen.MAGIC, java.nio.ByteBuffer.wrap(result.spirv())
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
        }

        @Test
        @DisplayName("Formats Elapsed")
        void formatsElapsed() {
            assertEquals("5 ms", result(true, 5L).elapsedText());
            assertTrue(result(true, 2500L).elapsedText().contains("s"));
        }

        @Test
        @DisplayName("Success Has No Report")
        void successHasNoReport() {
            assertTrue(result(true, 1L).report().isEmpty());
            assertFalse(result(false, 1L).report().isEmpty());
        }

        @Test
        @DisplayName("Has Binary Requires Both")
        void hasBinaryRequiresBoth() {
            assertTrue(result(true, 1L).hasBinary());
            assertFalse(ShaderCompileResult.ok("x", ShaderStage.COMPUTE, "main", new byte[0],
                    List.of(), 1L, false, "c").hasBinary());
        }

        private static ShaderCompileResult result(boolean success, long millis) {
            return success
                    ? ShaderCompileResult.ok("x", ShaderStage.COMPUTE, "main",
                    ComputeFillModule.bytes(), List.of(), millis, false, "c")
                    : ShaderCompileResult.fail("x", ShaderStage.COMPUTE, "main",
                    List.of(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR, "E1",
                            "出错了", "x.slang", 1, 1, "", List.of())), millis, "c", "Compilation failed");
        }
    }
}
