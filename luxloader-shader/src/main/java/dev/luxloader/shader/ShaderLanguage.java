package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Locale;

/**
 * Supported source languages share the compiler path, differing in suffixes and flags. GLSL and the
 * default SPIR-V optimization path require the bundled slang-glslang component. Slang shader
 * attributes still require command-line -entry. Explicitly align matrix conventions when sharing
 * uniforms across languages.
 */
public enum ShaderLanguage {

    /** Slang: default shader language with modules, generics and multiple backends. */
    SLANG("slang", ".slang"),
    /** GLSL for existing shader codebases. */
    GLSL("glsl", ".glsl"),
    /** HLSL shader sources. */
    HLSL("hlsl", ".hlsl");

    private final String key;
    private final String defaultExtension;

    ShaderLanguage(String key, String defaultExtension) {
        this.key = key;
        this.defaultExtension = defaultExtension;
    }

    /** Stable lowercase key for caches/configuration. */
    public String key() {
        return key;
    }

    /** Default temporary source suffix used for compiler frontend selection. */
    public String defaultExtension() {
        return defaultExtension;
    }

    /** Infers language from suffixes, including conventional GLSL .vert/.frag/.comp files. */
    public static ShaderLanguage fromExtension(String fileName) {
        if (fileName == null) {
            return SLANG;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return SLANG;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "slang" -> SLANG;
            case "glsl", "vert", "frag", "comp", "geom", "tesc", "tese",
                 "rgen", "rint", "rahit", "rchit", "rmiss", "rcall",
                 "task", "mesh", "vs", "fs", "cs" -> GLSL;
            case "hlsl", "fx", "hlsli" -> HLSL;
            default -> SLANG;
        };
    }

    /** Parses language names case-insensitively. */
    public static ShaderLanguage parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SLANG;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        for (ShaderLanguage l : values()) {
            if (l.key.equals(t)) {
                return l;
            }
        }
        throw new IllegalArgumentException(tr("Unknown shader language: ") + raw + tr(" (allowed: slang, glsl, hlsl)"));
    }
}
