package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.vulkan.SpirvGen;

import java.util.List;
import java.util.Objects;

/**
 * Compilation result.
 * @param name logical name
 * @param stage shader stage
 * @param entryPoint entry point
 * @param success whether compilation succeeded
 * @param spirv binary output, empty on failure
 * @param diagnostics structured diagnostics
 * @param elapsedMillis compilation or cache-read duration
 * @param fromCache whether output came from disk cache
 * @param usedCompiler compiler description, empty for precompiled input
 * @param error failure summary, empty on success
 */
public record ShaderCompileResult(
        String name,
        ShaderStage stage,
        String entryPoint,
        boolean success,
        byte[] spirv,
        List<ShaderDiagnostic> diagnostics,
        long elapsedMillis,
        boolean fromCache,
        String usedCompiler,
        String error) {

    public ShaderCompileResult {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(stage, "stage");
        entryPoint = entryPoint == null ? "main" : entryPoint;
        spirv = spirv == null ? new byte[0] : spirv.clone();
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        usedCompiler = usedCompiler == null ? "" : usedCompiler;
        error = error == null ? "" : error;
    }

    @Override
    public byte[] spirv() {
        return spirv.clone();
    }

    /** SPIR-V byte count. */
    public int size() {
        return spirv.length;
    }

    /** Whether compilation succeeded with a SPIR-V binary. */
    public boolean hasBinary() {
        return success && spirv.length > 0;
    }

    /** Human-readable compilation duration. */
    public String elapsedText() {
        if (elapsedMillis < 1000) {
            return elapsedMillis + " ms";
        }
        return String.format("%.2f s", elapsedMillis / 1000.0);
    }

    /** One-line description. */
    public String describe() {
        if (success) {
            return name + "：" + (fromCache ? tr("Cache hit") : tr("Compilation completed"))
                    + "（" + spirv.length + tr(" bytes, ") + elapsedText() + "）";
        }
        return name + tr(": compilation failed (") + ShaderDiagnostic.summarize(diagnostics) + "）";
    }

    /** Failure report with cause and individual diagnostics; empty on success. */
    public String report() {
        if (success) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(tr("Shader ")).append(name).append("（").append(stage.slangName())
                .append(tr("ShaderCompileResult.3df9401d9a", ", entry point ")).append(entryPoint).append(tr(") compilation failed"));
        if (!usedCompiler.isEmpty()) {
            sb.append(tr(", compiler: ")).append(usedCompiler);
        }
        if (!error.isEmpty()) {
            sb.append(System.lineSeparator()).append(tr("Cause: ")).append(error);
        }
        String detail = ShaderDiagnostic.render(diagnostics);
        if (!detail.isEmpty()) {
            sb.append(System.lineSeparator()).append(detail);
        }
        return sb.toString();
    }

    // Construction helpers.

    /** Successful compilation. */
    public static ShaderCompileResult ok(String name, ShaderStage stage, String entryPoint,
                                         byte[] spirv, List<ShaderDiagnostic> diagnostics,
                                         long elapsedMillis, boolean fromCache, String compiler) {
        return new ShaderCompileResult(name, stage, entryPoint, true, spirv, diagnostics,
                elapsedMillis, fromCache, compiler, "");
    }

    /** Creates success from validated precompiled SPIR-V. */
    public static ShaderCompileResult okPrecompiled(ShaderSource.Precompiled source,
                                                    long elapsedMillis) {
        return new ShaderCompileResult(source.name(), source.stage(), source.entryPoint(),
                true, source.spirv(), List.of(), elapsedMillis, false, "", "");
    }

    /** Compilation failure. */
    public static ShaderCompileResult fail(String name, ShaderStage stage, String entryPoint,
                                           List<ShaderDiagnostic> diagnostics, long elapsedMillis,
                                           String compiler, String error) {
        return new ShaderCompileResult(name, stage, entryPoint, false, new byte[0], diagnostics,
                elapsedMillis, false, compiler, error);
    }

    /** Structural failure before compiler execution. */
    public static ShaderCompileResult fail(String name, ShaderStage stage, String entryPoint,
                                           String error) {
        return fail(name, stage, entryPoint, List.of(), 0L, "", error);
    }

    /**
     * Converts structural SPIR-V validation failure without inventing source line/column positions for a
     * binary-level error.
     */
    public static ShaderCompileResult failValidation(String name, ShaderStage stage, String entryPoint,
                                                     SpirvGen.Validation validation) {
        ShaderDiagnostic d = new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR, "SPIR-V",
                validation.error(), "", 0, 0,
                tr("Structural validation failed. Malformed SPIR-V can crash the driver, ")
                        + tr("so the loader rejected it before driver submission."), List.of());
        return fail(name, stage, entryPoint, List.of(d), 0L, "", tr("SPIR-V structural validation failed"));
    }
}
