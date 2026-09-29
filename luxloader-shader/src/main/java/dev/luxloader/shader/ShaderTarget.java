package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Locale;
import java.util.Objects;

/**
 * Output format and SPIR-V target version. Only binary SPIR-V is passed to Vulkan; text targets
 * support diagnostics. Use forVulkan to select a compatible version and avoid driver rejection of
 * unsupported modules.
 */
public record ShaderTarget(String profile, Kind kind) {

    /** Output format. */
    public enum Kind {
        /** SPIR-V binary, directly consumable by Vulkan. */
        SPIRV("spirv"),
        /** SPIR-V assembly for diagnostics. */
        SPIRV_ASSEMBLY("spirv-assembly"),
        /** GLSL text for diagnostics. */
        GLSL("glsl"),
        /** HLSL text for diagnostics. */
        HLSL("hlsl");

        private final String slangName;

        Kind(String slangName) {
            this.slangName = slangName;
        }

        /** Name passed to slangc -target. */
        public String slangName() {
            return slangName;
        }
    }

    public ShaderTarget {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(kind, "kind");
        if (profile.isBlank()) {
            throw new IllegalArgumentException(tr("Target profile must not be empty"));
        }
    }

    /** SPIR-V 1.0 for Vulkan 1.0. */
    public static final ShaderTarget SPIRV_1_0 = spirv(1, 0);
    /** SPIR-V 1.3。 */
    public static final ShaderTarget SPIRV_1_3 = spirv(1, 3);
    /** SPIR-V 1.5 for Vulkan 1.2+. */
    public static final ShaderTarget SPIRV_1_5 = spirv(1, 5);
    /** SPIR-V 1.6 for Vulkan 1.3+. */
    public static final ShaderTarget SPIRV_1_6 = spirv(1, 6);

    /** Constructs a SPIR-V target. */
    public static ShaderTarget spirv(int major, int minor) {
        if (major < 1 || minor < 0) {
            throw new IllegalArgumentException(tr("Invalid SPIR-V version: ") + major + "." + minor);
        }
        return new ShaderTarget("spirv_" + major + "_" + minor, Kind.SPIRV);
    }

    /** Selects a conservative SPIR-V target: Vulkan 1.0 -> 1.0, 1.1 -> 1.3, 1.2 -> 1.5, and 1.3+ -> 1.6. */
    public static ShaderTarget forVulkan(int major, int minor) {
        if (major > 1 || (major == 1 && minor >= 3)) {
            return SPIRV_1_6;
        }
        if (major == 1 && minor == 2) {
            return SPIRV_1_5;
        }
        if (major == 1 && minor == 1) {
            return SPIRV_1_3;
        }
        return SPIRV_1_0;
    }

    /** Changes output format while retaining the SPIR-V version. */
    public ShaderTarget withKind(Kind newKind) {
        return new ShaderTarget(profile, newKind);
    }

    /** Whether output is directly consumable SPIR-V binary. */
    public boolean isBinarySpirv() {
        return kind == Kind.SPIRV;
    }

    /** Parses a profile such as spirv_1_5. */
    public static ShaderTarget parseProfile(String profile) {
        Objects.requireNonNull(profile, "profile");
        String p = profile.trim().toLowerCase(Locale.ROOT);
        if (p.startsWith("spirv_")) {
            String[] parts = p.substring("spirv_".length()).split("_");
            if (parts.length == 2) {
                try {
                    return spirv(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(tr("Cannot parse SPIR-V profile: ") + profile, e);
                }
            }
        }
        return new ShaderTarget(p, Kind.SPIRV);
    }

    @Override
    public String toString() {
        return profile + " (" + kind.slangName() + ")";
    }
}
