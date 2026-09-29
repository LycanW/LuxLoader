package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.vulkan.SpirvGen;

import java.nio.file.Path;
import java.util.List;

/**
 * Validates author-supplied SPIR-V without invoking external tools. Structural checks reject
 * malformed/truncated modules before drivers can crash the process. Full semantic validation remains
 * the responsibility of build-time spirv-val.
 */
public final class PrecompiledCompiler implements ShaderCompiler {

    /** Stateless singleton with no owned resources. */
    public static final PrecompiledCompiler INSTANCE = new PrecompiledCompiler();

    private PrecompiledCompiler() {
    }

    @Override
    public ShaderCompileResult compile(ShaderSource source, List<Path> includeDirs) {
        if (!(source instanceof ShaderSource.Precompiled precompiled)) {
            return ShaderCompileResult.fail(source.name(), source.stage(), source.entryPoint(),
                    tr("PrecompiledCompiler only accepts SPIR-V; use SlangCompiler for source code"));
        }
        long started = System.nanoTime();
        SpirvGen.Validation validation = SpirvGen.validate(precompiled.spirv());
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        if (!validation.valid()) {
            return ShaderCompileResult.failValidation(precompiled.name(), precompiled.stage(),
                    precompiled.entryPoint(), validation);
        }
        return new ShaderCompileResult(precompiled.name(), precompiled.stage(),
                precompiled.entryPoint(), true, precompiled.spirv(), List.of(), elapsed,
                false, "", "");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String describe() {
        return tr("Precompiled SPIR-V (structural validation without an external compiler)");
    }

    @Override
    public String cacheIdentity() {
        return "precompiled-spirv";
    }
}
