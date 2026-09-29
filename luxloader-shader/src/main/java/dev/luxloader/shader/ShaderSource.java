package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Shader input can be source compiled by the bundled toolchain and cached, or precompiled SPIR-V
 * validated without a compiler. Both feed identical pipeline creation; the choice concerns
 * packaging/startup tradeoffs.
 */
public sealed interface ShaderSource {

    /** Logical name for logs, diagnostics and cache files. */
    String name();

    /** Shader stage. */
    ShaderStage stage();

    /** Entry point. */
    String entryPoint();

    /**
     * Source for runtime compilation.
     * @param name logical name
     * @param stage shader stage
     * @param entryPoint entry point
     * @param source text, nullable when origin supplies a file
     * @param language source language
     * @param defines preprocessor defines
     * @param target output target
     * @param extraArguments raw compiler arguments for options outside this API
     * @param origin optional source path establishing relative include resolution
     */
    record FromSource(
            String name,
            ShaderStage stage,
            String entryPoint,
            String source,
            ShaderLanguage language,
            Map<String, String> defines,
            ShaderTarget target,
            List<String> extraArguments,
            Path origin) implements ShaderSource {

        public FromSource {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(stage, "stage");
            entryPoint = entryPoint == null || entryPoint.isBlank() ? "main" : entryPoint.trim();
            language = language == null ? ShaderLanguage.SLANG : language;
            defines = defines == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(defines));
            target = target == null ? ShaderTarget.SPIRV_1_0 : target;
            extraArguments = extraArguments == null ? List.of() : List.copyOf(extraArguments);
            if (source == null && origin == null) {
                throw new IllegalArgumentException(tr("Shader ") + name + tr(": provide source text or a source file path"));
            }
        }

        /** Convenience source: Slang, SPIR-V 1.0, entry main. */
        public static FromSource of(String name, ShaderStage stage, String source) {
            return new FromSource(name, stage, "main", source, ShaderLanguage.SLANG,
                    Map.of(), ShaderTarget.SPIRV_1_0, List.of(), null);
        }

        /** Compiles a disk source with includes relative to its directory. */
        public static FromSource fromFile(Path file, ShaderStage stage, String entryPoint) {
            Objects.requireNonNull(file, "file");
            String fileName = file.getFileName().toString();
            ShaderStage s = stage == null ? ShaderStage.fromExtension(fileName) : stage;
            if (s == null) {
                throw new IllegalArgumentException(tr("Cannot infer shader stage from filename; specify it explicitly: ") + fileName);
            }
            return new FromSource(fileName, s, entryPoint, null,
                    ShaderLanguage.fromExtension(fileName), Map.of(),
                    ShaderTarget.SPIRV_1_0, List.of(), file);
        }

        /** Copies with a different target. */
        public FromSource withTarget(ShaderTarget newTarget) {
            return new FromSource(name, stage, entryPoint, source, language, defines,
                    newTarget, extraArguments, origin);
        }

        /** Copies with an additional define. */
        public FromSource withDefine(String key, String value) {
            Map<String, String> m = new LinkedHashMap<>(defines);
            m.put(key, value);
            return new FromSource(name, stage, entryPoint, source, language, m,
                    target, extraArguments, origin);
        }

        /** Copies with a different entry point. */
        public FromSource withEntryPoint(String newEntryPoint) {
            return new FromSource(name, stage, newEntryPoint, source, language, defines,
                    target, extraArguments, origin);
        }

        /**
         * Returns source text, reading origin lazily when source is null so descriptor construction does not
         * perform I/O.
         */
        public String readSource() throws IOException {
            if (source != null) {
                return source;
            }
            return Files.readString(origin);
        }

        /** Source directory for relative includes. */
        public Optional<Path> sourceDirectory() {
            return origin == null ? Optional.empty() : Optional.ofNullable(origin.getParent());
        }
    }

    /**
     * Author-supplied SPIR-V, still structurally validated before driver use to avoid process crashes on
     * malformed modules.
     * @param name logical name
     * @param stage shader stage
     * @param entryPoint entry point
     * @param spirv binary module
     */
    record Precompiled(
            String name,
            ShaderStage stage,
            String entryPoint,
            byte[] spirv) implements ShaderSource {

        public Precompiled {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(stage, "stage");
            entryPoint = entryPoint == null || entryPoint.isBlank() ? "main" : entryPoint.trim();
            Objects.requireNonNull(spirv, "spirv");
            spirv = spirv.clone();
        }

        @Override
        public byte[] spirv() {
            return spirv.clone();
        }

        /** Reads a .spv file. */
        public static Precompiled fromFile(Path file, ShaderStage stage, String entryPoint)
                throws IOException {
            Objects.requireNonNull(file, "file");
            String fileName = file.getFileName().toString();
            ShaderStage s = stage == null ? ShaderStage.fromExtension(fileName) : stage;
            if (s == null) {
                throw new IllegalArgumentException(tr("Cannot infer shader stage from filename; specify it explicitly: ") + fileName);
            }
            return new Precompiled(fileName, s, entryPoint, Files.readAllBytes(file));
        }

        /** Reads classpath SPIR-V, e.g. /mymod/shaders/bloom.comp.spv. */
        public static Precompiled fromClasspath(String resourcePath, ShaderStage stage,
                                                String entryPoint) throws IOException {
            Objects.requireNonNull(resourcePath, "resourcePath");
            try (var in = ShaderSource.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new IOException(tr("Shader not found on classpath: ") + resourcePath);
                }
                String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
                ShaderStage s = stage == null ? ShaderStage.fromExtension(fileName) : stage;
                if (s == null) {
                    throw new IllegalArgumentException(tr("Cannot infer shader stage from filename; specify it explicitly: ") + fileName);
                }
                return new Precompiled(fileName, s, entryPoint, in.readAllBytes());
            }
        }
    }

    /** Whether source compilation is required. */
    default boolean needsCompiler() {
        return this instanceof FromSource;
    }

    /** One-line log/diagnostic description. */
    default String describe() {
        if (this instanceof FromSource s) {
            return s.name() + " [" + s.stage().slangName() + "/" + s.language().key()
                    + " → " + s.target().profile() + "]";
        }
        Precompiled p = (Precompiled) this;
        return p.name() + " [" + p.stage().slangName() + tr("/compiled ") + p.spirv().length + tr(" bytes]");
    }
}
