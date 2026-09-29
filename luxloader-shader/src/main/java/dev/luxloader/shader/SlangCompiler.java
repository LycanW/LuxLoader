package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.vulkan.SpirvGen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Invokes slangc with explicit target/profile/entry/stage/output/include/define arguments. Use vertex
 * rather than vert for stage selection; E50011 for an old SPIR-V profile is a warning, not failure.
 * Preprocessor defines are passed with -D. Temporary source paths are rewritten to logical filenames
 * in diagnostics.
 */
public final class SlangCompiler implements ShaderCompiler {

    /** Default timeout before terminating a stalled compiler. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final SlangToolchain.Resolved toolchain;
    private final Path workRoot;
    private final Duration timeout;
    private final boolean debugInfo;
    private final boolean keepWorkFiles;
    private final AtomicLong sequence = new AtomicLong();

    private SlangCompiler(SlangToolchain.Resolved toolchain, Path workRoot, Duration timeout,
                          boolean debugInfo, boolean keepWorkFiles) {
        this.toolchain = toolchain;
        this.workRoot = workRoot;
        this.timeout = timeout;
        this.debugInfo = debugInfo;
        this.keepWorkFiles = keepWorkFiles;
    }

    /**
     * Finds and constructs a compiler.
     * @param explicitPath optional explicit executable
     * @param workRoot root for temporary source/output files
     * @return empty if unavailable
     */
    public static Optional<SlangCompiler> detect(Path explicitPath, Path workRoot) {
        return SlangToolchain.locate(explicitPath)
                .map(resolved -> new SlangCompiler(resolved, workRoot,
                        DEFAULT_TIMEOUT, false, false));
    }

    /** Constructs from a known executable location. */
    public static SlangCompiler of(SlangToolchain.Resolved toolchain, Path workRoot) {
        return new SlangCompiler(toolchain, workRoot, DEFAULT_TIMEOUT, false, false);
    }

    /** Includes debug data in SPIR-V, increasing size but aiding GPU fault diagnosis. */
    public SlangCompiler withDebugInfo(boolean enabled) {
        return new SlangCompiler(toolchain, workRoot, timeout, enabled, keepWorkFiles);
    }

    /** Per-compilation timeout. */
    public SlangCompiler withTimeout(Duration newTimeout) {
        return new SlangCompiler(toolchain, workRoot, newTimeout, debugInfo, keepWorkFiles);
    }

    /** Preserves intermediate files for diagnosis instead of normal cleanup. */
    public SlangCompiler withKeepWorkFiles(boolean keep) {
        return new SlangCompiler(toolchain, workRoot, timeout, debugInfo, keep);
    }

    /** Underlying toolchain information. */
    public SlangToolchain.Resolved toolchain() {
        return toolchain;
    }

    @Override
    public boolean available() {
        return Files.isRegularFile(toolchain.executable());
    }

    @Override
    public String describe() {
        return toolchain.describe();
    }

    @Override
    public String cacheIdentity() {
        return "slangc:" + toolchain.version() + ":" + toolchain.origin().name()
                + ":" + toolchain.executable().toAbsolutePath().normalize();
    }

    @Override
    public ShaderCompileResult compile(ShaderSource source, List<Path> includeDirs) {
        if (!(source instanceof ShaderSource.FromSource fromSource)) {
            return ShaderCompileResult.fail(source.name(), source.stage(), source.entryPoint(),
                    tr("SlangCompiler only accepts source; use PrecompiledCompiler for SPIR-V"));
        }
        long started = System.nanoTime();
        Path work = null;
        try {
            work = Files.createDirectories(workRoot.resolve("compile-" + sequence.incrementAndGet()));
            Path input = materialize(fromSource, work);
            Path output = work.resolve("out.spv");
            List<String> command = buildCommand(fromSource, input, output, includeDirs);

            ProcessResult result = run(command, work);
            long elapsed = (System.nanoTime() - started) / 1_000_000L;

            if (!result.ok()) {
                List<ShaderDiagnostic> diagnostics = parseDiagnostics(result.output(), work, fromSource);
                String error = diagnostics.stream().anyMatch(ShaderDiagnostic::isError)
                        ? tr("Compiler returned ") + result.exitCode() + tr("; see diagnostics below")
                        : tr("Compiler returned ") + result.exitCode() + tr(" without a parseable error; raw output is attached to diagnostics");
                return ShaderCompileResult.fail(fromSource.name(), fromSource.stage(),
                        fromSource.entryPoint(), diagnostics, elapsed, describe(), error);
            }

            if (!Files.isRegularFile(output)) {
                return ShaderCompileResult.fail(fromSource.name(), fromSource.stage(),
                        fromSource.entryPoint(), parseDiagnostics(result.output(), work, fromSource),
                        elapsed, describe(),
                        tr("Compiler reported success but produced no output; check that the -o path is writable"));
            }

            byte[] spirv = Files.readAllBytes(output);
            List<ShaderDiagnostic> diagnostics = parseDiagnostics(result.output(), work, fromSource);

            SpirvGen.Validation validation = SpirvGen.validate(spirv);
            if (!validation.valid()) {
                // Validate compiler output before passing it to a driver that may crash on malformed modules.
                ShaderCompileResult failed = ShaderCompileResult.failValidation(
                        fromSource.name(), fromSource.stage(), fromSource.entryPoint(), validation);
                return new ShaderCompileResult(failed.name(), failed.stage(), failed.entryPoint(),
                        false, new byte[0], diagnostics, elapsed, false, describe(), failed.error());
            }

            return ShaderCompileResult.ok(fromSource.name(), fromSource.stage(),
                    fromSource.entryPoint(), spirv, diagnostics, elapsed, false, describe());
        } catch (IOException e) {
            return ShaderCompileResult.fail(source.name(), source.stage(), source.entryPoint(),
                    tr("I/O error during compilation: ") + e);
        } finally {
            if (work != null && !keepWorkFiles) {
                deleteRecursively(work);
            }
        }
    }

    // Argument construction.

    /**
     * Compiles file-backed source in place for correct relative includes. Writes in-memory source to the
     * work directory with the language-specific suffix.
     */
    private Path materialize(ShaderSource.FromSource source, Path work) throws IOException {
        if (source.origin() != null) {
            return source.origin().toAbsolutePath();
        }
        Path file = work.resolve(safeFileName(source));
        Files.writeString(file, source.readSource(), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Converts logical names to safe filenames with a correct language suffix. Preserve existing
     * same-language suffixes such as GLSL .comp so frontend selection and diagnostics remain familiar.
     */
    static String safeFileName(ShaderSource.FromSource source) {
        String base = source.name().replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty()) {
            base = "shader";
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0 && ShaderLanguage.fromExtension(base) == source.language()) {
            return base;
        }
        return base + source.language().defaultExtension();
    }

    /** Constructs the command line, exposed to tests for argument verification. */
    static List<String> buildCommand(ShaderSource.FromSource source, Path input, Path output,
                                     List<Path> includeDirs) {
        ShaderTarget target = source.target();
        List<String> cmd = new ArrayList<>();
        cmd.add(input.toString());
        cmd.add("-target");
        cmd.add(target.kind().slangName());
        cmd.add("-profile");
        cmd.add(target.profile());
        cmd.add("-entry");
        cmd.add(source.entryPoint());
        cmd.add("-stage");
        cmd.add(source.stage().slangName());
        cmd.add("-o");
        cmd.add(output.toString());

        if (includeDirs != null) {
            for (Path dir : includeDirs) {
                if (dir != null) {
                    cmd.add("-I");
                    cmd.add(dir.toAbsolutePath().toString());
                }
            }
        }
        // Always search the source directory for relative includes.
        source.sourceDirectory().ifPresent(dir -> {
            cmd.add("-I");
            cmd.add(dir.toAbsolutePath().toString());
        });

        // Sort defines by name for reproducible command lines and cache identity.
        List<Map.Entry<String, String>> defines = new ArrayList<>(source.defines().entrySet());
        defines.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, String> e : defines) {
            cmd.add("-D");
            cmd.add(e.getValue() == null || e.getValue().isEmpty()
                    ? e.getKey() : e.getKey() + "=" + e.getValue());
        }

        cmd.addAll(source.extraArguments());
        return cmd;
    }

    // Process execution.

    /** Process execution result. */
    record ProcessResult(int exitCode, String output, boolean timedOut) {
        boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    private ProcessResult run(List<String> arguments, Path workDir) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(toolchain.executable().toString());
        command.addAll(arguments);
        if (debugInfo) {
            command.add("-g");
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Path libDir = toolchain.libraryDir() != null ? toolchain.libraryDir() : toolchain.directory();
        pb.environment().putAll(SlangToolchain.libraryPathEnv(libDir));

        Process process = pb.start();
        process.getOutputStream().close();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new ProcessResult(-1, output + System.lineSeparator()
                        + tr("Compiler did not return within ") + timeout.toSeconds() + tr(" seconds and was terminated. ")
                        + tr("Check for include cycles or infinitely expanding macros."), true);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, output, true);
        }
        return new ProcessResult(process.exitValue(), output, false);
    }

    // Diagnostics.

    /** Parses compiler diagnostics and maps temporary source/output paths back to logical names. */
    private List<ShaderDiagnostic> parseDiagnostics(String output, Path work,
                                                    ShaderSource.FromSource source) {
        String logical = source.name();
        String workPrefix = work.toAbsolutePath().toString();
        Path originParent = source.origin() == null ? null : source.origin().toAbsolutePath().getParent();
        String originPrefix = originParent == null ? null : originParent.toString();

        return ShaderDiagnostic.parseAll(output, file -> {
            if (file.startsWith(workPrefix)) {
                return logical;
            }
            if (originPrefix != null && file.startsWith(originPrefix)) {
                return logical;
            }
            return file;
        });
    }

    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Cleanup failure does not affect compilation results.
                }
            });
        } catch (IOException ignored) {
            // Same cleanup policy.
        }
    }

    /** Writes in-memory shader source to a compiler input file. */
    static Path writeSource(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    @Override
    public String toString() {
        return "SlangCompiler{" + toolchain.describe() + "}";
    }
}
