package dev.luxloader.shader;

import dev.luxloader.api.vulkan.SpirvGen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests invoking slangc to verify command-line arguments, frontends, includes, macros and
 * diagnostics. Skip when unavailable; select an executable with -Dluxloader.test.slangc=<path>.
 */
class SlangCompilerRealTest {

    private static final Optional<Path> TOOLCHAIN = SlangTestToolchain.find();

    @TempDir
    Path tempDir;

    private SlangCompiler compiler;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TOOLCHAIN.isPresent(),
                "No slangc found; skipping real compiler tests. Select it with -D" + SlangTestToolchain.PROP + "=<path>");
        compiler = SlangCompiler.detect(TOOLCHAIN.orElseThrow(), tempDir.resolve("work"))
                .orElseThrow(() -> new IllegalStateException("编译器存在却构造失败"));
    }

    @AfterEach
    void tearDown() {
        if (compiler != null) {
            compiler.close();
        }
    }

    /** Minimal complete Slang compute shader. */
    private static final String SLANG_COMPUTE = """
            [shader("compute")]
            [numthreads(8, 8, 1)]
            void main(uint3 id : SV_DispatchThreadID)
            {
            }
            """;

    /** Equivalent minimal GLSL compute shader. */
    private static final String GLSL_COMPUTE = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;
            void main() { }
            """;

    @Test
    @DisplayName("Compiles Slang Source")
    void compilesSlangSource() {
        ShaderCompileResult result = compiler.compile(
                ShaderSource.FromSource.of("minimal", ShaderStage.COMPUTE, SLANG_COMPUTE), List.of());

        assertTrue(result.success(), () -> "Compilation must succeed. Report: " + result.report());
        assertTrue(result.hasBinary());
        assertEquals(0, result.spirv().length % 4, "SPIR-V length must be divisible by four");

        SpirvGen.Validation validation = SpirvGen.validate(result.spirv());
        assertTrue(validation.valid(), "The artifact must pass structural validation: " + validation.error());
        assertTrue(validation.instructionCount() > 0);

        // Check SPIR-V magic before passing bytes to a driver.
        int magic = java.nio.ByteBuffer.wrap(result.spirv())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        assertEquals(SpirvGen.MAGIC, magic);
    }

    @Test
    @DisplayName("Compiles Glsl Source")
    void compilesGlslSource() {
        ShaderSource.FromSource source = new ShaderSource.FromSource("glsl-min", ShaderStage.COMPUTE,
                "main", GLSL_COMPUTE, ShaderLanguage.GLSL, java.util.Map.of(),
                ShaderTarget.SPIRV_1_5, List.of(), null);

        ShaderCompileResult result = compiler.compile(source, List.of());

        assertTrue(result.success(), () -> "Compilation must succeed. E00100 or a downstream-compiler load failure can indicate missing slang-glslang, also required by the default Slang optimization path. Report: " + result.report());
        assertTrue(SpirvGen.validate(result.spirv()).valid());
    }

    @Test
    @DisplayName("Compiles Glsl Vertex Stage")
    void compilesGlslVertexStage() {
        String vertex = """
                #version 450
                layout(location=0) out vec2 uv;
                void main() {
                    uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
                }
                """;
        ShaderSource.FromSource source = new ShaderSource.FromSource("fullscreen.vert",
                ShaderStage.VERTEX, "main", vertex, ShaderLanguage.GLSL, java.util.Map.of(),
                ShaderTarget.SPIRV_1_0, List.of(), null);

        ShaderCompileResult result = compiler.compile(source, List.of());

        assertTrue(result.success(), () -> "Compilation must succeed. Report: " + result.report());
        assertTrue(SpirvGen.validate(result.spirv()).valid());
    }

    @Test
    @DisplayName("Passes Includes And Defines")
    void passesIncludesAndDefines() throws IOException {
        Path includeDir = Files.createDirectories(tempDir.resolve("include"));
        Files.writeString(includeDir.resolve("common.h"), "#define FROM_INCLUDE 1\n",
                StandardCharsets.UTF_8);

        String source = """
                #include "common.h"
                [shader("compute")]
                [numthreads(8, 8, 1)]
                void main(uint3 id : SV_DispatchThreadID)
                {
                #if !defined(MY_DEFINE)
                #error MY_DEFINE missing
                #endif
                #if FROM_INCLUDE != 1
                #error include failed
                #endif
                }
                """;

        ShaderSource.FromSource withDefine = new ShaderSource.FromSource("inc_test", ShaderStage.COMPUTE,
                "main", source, ShaderLanguage.SLANG, java.util.Map.of("MY_DEFINE", "1"),
                ShaderTarget.SPIRV_1_5, List.of(), null);

        ShaderCompileResult ok = compiler.compile(withDefine, List.of(includeDir));
        assertTrue(ok.success(), () -> "Compilation with includes and macros must succeed. Report: " + ok.report());

        // Compilation without the macro must fail, proving the successful case actually used -D.
        ShaderSource.FromSource withoutDefine = new ShaderSource.FromSource("inc_test", ShaderStage.COMPUTE,
                "main", source, ShaderLanguage.SLANG, java.util.Map.of(),
                ShaderTarget.SPIRV_1_5, List.of(), null);
        ShaderCompileResult failed = compiler.compile(withoutDefine, List.of(includeDir));

        assertFalse(failed.success(), "Omitting the macro must trigger #error and fail compilation");
        assertTrue(failed.diagnostics().stream().anyMatch(d -> d.message().contains("preprocessor")),
                "Expected a preprocessing error: " + failed.diagnostics());
    }

    @Test
    @DisplayName("Reports Structured Diagnostics With Logical Name")
    void reportsStructuredDiagnosticsWithLogicalName() {
        String broken = """
                [shader("compute")]
                [numthreads(8, 8, 1)]
                void main(uint3 id : SV_DispatchThreadID)
                {
                    uint x = undefinedThing;
                }
                """;
        ShaderCompileResult result = compiler.compile(
                ShaderSource.FromSource.of("我的着色器", ShaderStage.COMPUTE, broken), List.of());

        assertFalse(result.success(), "This source must fail compilation");
        assertFalse(result.report().isBlank(), "Failed compilation must include a readable report");

        ShaderDiagnostic error = result.diagnostics().stream()
                .filter(ShaderDiagnostic::isError)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected at least one error diagnostic: "
                        + result.diagnostics()));

        assertEquals("我的着色器", error.file(),
                "Diagnostics must use the logical source name rather than a temporary path");
        assertTrue(error.line() > 0, "Diagnostics must identify a source line: " + error);
        assertFalse(error.hint().isBlank(), "Provide Chinese guidance in the Chinese locale");
        assertTrue(result.report().contains("我的着色器"), result.report());
    }

    @Test
    @DisplayName("Reports Missing File")
    void reportsMissingFile() {
        ShaderSource.FromSource source = ShaderSource.FromSource.fromFile(
                tempDir.resolve("nope.slang"), ShaderStage.COMPUTE, "main");

        ShaderCompileResult result = compiler.compile(source, List.of());

        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("nope.slang")),
                "The error must name the missing file: " + result.diagnostics());
    }

    @Test
    @DisplayName("Builds Expected Command Line")
    void buildsExpectedCommandLine() {
        ShaderSource.FromSource source = new ShaderSource.FromSource("bloom", ShaderStage.COMPUTE,
                "cs_main", "// x", ShaderLanguage.SLANG, java.util.Map.of("QUALITY", "2", "DEBUG", ""),
                ShaderTarget.SPIRV_1_5, List.of("-fvk-use-entrypoint-name"), null);

        List<String> cmd = SlangCompiler.buildCommand(source,
                Path.of("in.slang"), Path.of("out.spv"), List.of(Path.of("inc")));

        assertEquals("in.slang", cmd.get(0), "Place the input file first");
        assertTrue(indexOfPair(cmd, "-target", "spirv") >= 0, cmd.toString());
        assertTrue(indexOfPair(cmd, "-profile", "spirv_1_5") >= 0, cmd.toString());
        assertTrue(indexOfPair(cmd, "-entry", "cs_main") >= 0, cmd.toString());
        assertTrue(indexOfPair(cmd, "-stage", "compute") >= 0, cmd.toString());
        assertTrue(indexOfPair(cmd, "-o", "out.spv") >= 0, cmd.toString());
        assertTrue(indexOfPair(cmd, "-I", Path.of("inc").toAbsolutePath().toString()) >= 0, cmd.toString());
        assertTrue(cmd.contains("-D"), cmd.toString());
        assertTrue(cmd.contains("QUALITY=2"), "Macros with values must use KEY=VALUE: " + cmd);
        assertTrue(cmd.contains("DEBUG"), "Macros without values must use KEY: " + cmd);
        assertTrue(cmd.contains("-fvk-use-entrypoint-name"), "Pass extra arguments through unchanged: " + cmd);

        // Sort macros for reproducible command lines and cache keys.
        List<String> again = SlangCompiler.buildCommand(source,
                Path.of("in.slang"), Path.of("out.spv"), List.of(Path.of("inc")));
        assertEquals(cmd, again);
    }

    @Test
    @DisplayName("Compiles In Place When Source Is AFile")
    void compilesInPlaceWhenSourceIsAFile() throws IOException {
        Path shaders = Files.createDirectories(tempDir.resolve("shaders"));
        Files.writeString(shaders.resolve("shared.slang"), """
                public static const uint GROUP = 4;
                """, StandardCharsets.UTF_8);
        Path main = shaders.resolve("main.slang");
        Files.writeString(main, """
                #include "shared.slang"
                [shader("compute")]
                [numthreads(GROUP, 1, 1)]
                void main(uint3 id : SV_DispatchThreadID) { }
                """, StandardCharsets.UTF_8);

        ShaderSource.FromSource source = ShaderSource.FromSource.fromFile(main, ShaderStage.COMPUTE, "main");
        ShaderCompileResult result = compiler.compile(source, List.of());

        assertTrue(result.success(), () -> "Relative includes beside the source must work without extra configuration. Report: "
                + result.report());
    }

    @Test
    @DisplayName("Derives Safe File Names")
    void derivesSafeFileNames() {
        assertEquals("bloom.slang", SlangCompiler.safeFileName(
                ShaderSource.FromSource.of("bloom", ShaderStage.COMPUTE, "// x")));
        assertEquals("bloom.slang", SlangCompiler.safeFileName(
                ShaderSource.FromSource.of("dir/bloom.slang", ShaderStage.COMPUTE, "// x")));
        assertEquals("bloom.comp", SlangCompiler.safeFileName(new ShaderSource.FromSource(
                "bloom.comp", ShaderStage.COMPUTE, "main", "// x", ShaderLanguage.GLSL,
                java.util.Map.of(), ShaderTarget.SPIRV_1_0, List.of(), null)));
        assertEquals("wei_yi.slang", SlangCompiler.safeFileName(
                ShaderSource.FromSource.of("wei yi", ShaderStage.COMPUTE, "// x")));
    }

    @Test
    @DisplayName("Description Carries Version")
    void descriptionCarriesVersion() {
        String description = compiler.describe();
        assertTrue(description.contains("slangc"), description);
        assertFalse(compiler.toolchain().version().isEmpty(),
                "A usable compiler must report its version");
        assertTrue(description.contains(compiler.toolchain().version()), description);
    }

    @Test
    @DisplayName("Version Query Is Safe")
    void versionQueryIsSafe() {
        assertEquals("", SlangToolchain.queryVersion(tempDir.resolve("no-such-slangc" + (
                SlangToolchain.platform().startsWith("windows") ? ".exe" : ""))));
    }

    @Test
    @DisplayName("Cleans Up Work Directory")
    void cleansUpWorkDirectory() throws IOException {
        Path work = tempDir.resolve("work-clean");
        SlangCompiler scoped = SlangCompiler.detect(TOOLCHAIN.orElseThrow(), work).orElseThrow();
        try {
            assertTrue(scoped.compile(ShaderSource.FromSource.of("c", ShaderStage.COMPUTE,
                    SLANG_COMPUTE), List.of()).success());
        } finally {
            scoped.close();
        }
        if (Files.isDirectory(work)) {
            try (var entries = Files.list(work)) {
                assertEquals(0, entries.count(),
                        "Remove intermediate files to prevent unbounded work-directory growth");
            }
        }
    }

    @Test
    @DisplayName("Keeps Work Files On Demand")
    void keepsWorkFilesOnDemand() throws IOException {
        Path work = tempDir.resolve("work-keep");
        SlangCompiler scoped = SlangCompiler.detect(TOOLCHAIN.orElseThrow(), work).orElseThrow()
                .withKeepWorkFiles(true);
        try {
            assertTrue(scoped.compile(ShaderSource.FromSource.of("c", ShaderStage.COMPUTE,
                    SLANG_COMPUTE), List.of()).success());
            try (var entries = Files.list(work)) {
                assertTrue(entries.count() > 0, "Preserve intermediate files when requested");
            }
        } finally {
            scoped.close();
        }
    }

    private static int indexOfPair(List<String> list, String flag, String value) {
        for (int i = 0; i + 1 < list.size(); i++) {
            if (list.get(i).equals(flag) && list.get(i + 1).equals(value)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("Unavailable Compiler Explains What To Do")
    void unavailableCompilerExplainsWhatToDo() {
        ShaderCompiler none = ShaderCompiler.unavailable("测试用：没有编译器");
        assertFalse(none.available());

        ShaderCompileResult result = none.compile(
                ShaderSource.FromSource.of("x", ShaderStage.COMPUTE, "// x"), List.of());

        assertFalse(result.success());
        String report = result.report();
        assertTrue(report.contains("shader.slangcPath"), report);
        assertTrue(report.contains(SlangToolchain.ENV_SLANGC), report);
        assertTrue(report.contains("预编译"), report);
    }

    @Test
    @DisplayName("Locates Explicit Toolchain")
    void locatesExplicitToolchain() {
        Optional<SlangToolchain.Resolved> resolved =
                SlangToolchain.locate(TOOLCHAIN.orElseThrow());
        assertTrue(resolved.isPresent());
        assertEquals(SlangToolchain.Resolved.Origin.EXPLICIT, resolved.get().origin());
        assertNotNull(resolved.get().libraryDir());
        assertTrue(resolved.get().describe().contains("显式指定"), resolved.get().describe());
    }

    @Test
    @DisplayName("Rejects Bogus Explicit Path")
    void rejectsBogusExplicitPath() {
        Optional<SlangToolchain.Resolved> resolved =
                SlangToolchain.locate(tempDir.resolve("definitely-not-here"));
        assertTrue(resolved.isEmpty()
                        || resolved.get().origin() != SlangToolchain.Resolved.Origin.EXPLICIT,
                "A missing explicit executable must not report EXPLICIT origin: " + resolved);
    }

    @Test
    @DisplayName("Bundled Toolchain Works End To End")
    void bundledToolchainWorksEndToEnd() throws IOException {
        String platform = SlangToolchain.platform();
        String resource = "/native/" + platform + "/" + SlangToolchain.executableName();
        Assumptions.assumeTrue(SlangToolchain.class.getResource(resource) != null,
                "No bundled compiler for " + platform + "; skipping (run "
                        + ":luxloader-shader:fetchSlangc to obtain it)");

        Path cacheRoot = tempDir.resolve("cache-root");
        SlangToolchain.Resolved bundled = SlangToolchain.extractBundled(
                        cacheRoot, platform, SlangToolchain.class)
                .orElseThrow(() -> new AssertionError("Resource exists but extraction failed"));

        assertEquals(SlangToolchain.Resolved.Origin.BUNDLED, bundled.origin());
        assertTrue(bundled.version().matches("\\d{4}\\.\\d+.*"),
                "Expected a version such as 2026.18: " + bundled.version());
        assertTrue(Files.isRegularFile(bundled.executable()));
        // Dependencies must accompany the executable for Windows DLL lookup and Unix library search paths.
        try (var siblings = Files.list(bundled.libraryDir())) {
            long libraryCount = siblings
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib");
                    })
                    .count();
            assertTrue(libraryCount >= 1,
                    "Extract runtime libraries alongside the executable");
        }

        // Require BUNDLED origin to prove the test did not silently use a compiler from PATH.
        Path previousCacheDir = null;
        String previousProperty = System.getProperty("luxloader.cacheDir");
        try {
            System.setProperty("luxloader.cacheDir", cacheRoot.toString());
            Optional<SlangToolchain.Resolved> located = SlangToolchain.locate(null);
            assertTrue(located.isPresent());
            assertEquals(SlangToolchain.Resolved.Origin.BUNDLED, located.get().origin(),
                    "A bundled compiler must take precedence over uncontrolled PATH versions");
        } finally {
            if (previousProperty == null) {
                System.clearProperty("luxloader.cacheDir");
            } else {
                System.setProperty("luxloader.cacheDir", previousProperty);
            }
        }

        SlangCompiler bundledCompiler = SlangCompiler
                .of(bundled, tempDir.resolve("bundled-work"));
        try {
            ShaderCompileResult result = bundledCompiler.compile(
                    ShaderSource.FromSource.of("bundled", ShaderStage.COMPUTE, SLANG_COMPUTE),
                    List.of());

            assertTrue(result.success(), () -> "The bundled distribution must compile SPIR-V. A failure to load spirv-opt can indicate missing slang-glslang. Report: " + result.report());
            assertTrue(SpirvGen.validate(result.spirv()).valid());
        } finally {
            bundledCompiler.close();
        }
    }

    @Test
    @DisplayName("Bundled Extraction Is Cached")
    void bundledExtractionIsCached() throws IOException {
        String platform = SlangToolchain.platform();
        String resource = "/native/" + platform + "/" + SlangToolchain.executableName();
        Assumptions.assumeTrue(SlangToolchain.class.getResource(resource) != null,
                "No compiler bundled for this platform");

        Path cacheRoot = tempDir.resolve("cache-root-2");
        SlangToolchain.Resolved first = SlangToolchain.extractBundled(
                cacheRoot, platform, SlangToolchain.class).orElseThrow();

        long modifiedAt = Files.getLastModifiedTime(first.executable()).toMillis();
        SlangToolchain.Resolved second = SlangToolchain.extractBundled(
                cacheRoot, platform, SlangToolchain.class).orElseThrow();

        assertEquals(first.executable(), second.executable());
        assertEquals(modifiedAt, Files.getLastModifiedTime(second.executable()).toMillis(),
                "The second extraction must not rewrite unchanged files");
    }
}
