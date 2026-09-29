package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import java.nio.file.Path;
import java.util.List;

/**
 * Thread-safe shader compiler contract used concurrently by background workers. SlangCompiler handles
 * source through an external process; PrecompiledCompiler validates supplied binaries. ShaderLibrary
 * selects the appropriate path.
 */
public interface ShaderCompiler {

    /**
     * Compiles a shader. Ordinary compile failures return ShaderCompileResult with diagnostics; reserve
     * runtime exceptions for environmental failures.
     * @param source shader
     * @param includeDirs extra include directories
     * @return compilation result
     */
    ShaderCompileResult compile(ShaderSource source, List<Path> includeDirs);

    /** Whether the compiler can run. */
    boolean available();

    /** Human-readable versioned description for logs and diagnostics. Use cacheIdentity() for cache keys. */
    String describe();

    /** Stable compiler identity for caches; include the version and exclude localized labels. */
    default String cacheIdentity() {
        return describe();
    }

    /** Releases resources; default no-op. */
    default void close() {
    }

    /**
     * Unavailable compiler placeholder carrying a failure reason, while leaving precompiled SPIR-V usable.
     * @param reason explanation included in failures
     */
    static ShaderCompiler unavailable(String reason) {
        String message = reason == null || reason.isBlank() ? tr("No usable shader compiler found") : reason;
        return new ShaderCompiler() {
            @Override
            public ShaderCompileResult compile(ShaderSource source, List<Path> includeDirs) {
                return ShaderCompileResult.fail(source.name(), source.stage(), source.entryPoint(),
                        tr("No usable shader compiler found: ") + message
                                + tr(". Set shader.slangcPath, ")
                                + tr("set environment variable ") + SlangToolchain.ENV_SLANGC + "、"
                                + tr("or supply precompiled .spv files."));
            }

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String describe() {
                return tr("Unavailable (") + message + "）";
            }

            @Override
            public String cacheIdentity() {
                return "unavailable";
            }
        };
    }
}
