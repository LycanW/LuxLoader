package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Locale;

/**
 * Shader stages with both slangc -stage names and Vulkan flags, centralized to prevent inconsistent
 * mappings such as raygeneration/RAYGEN_BIT_KHR.
 */
public enum ShaderStage {

    /** Vertex stage. */
    VERTEX("vertex"),
    /** Tessellation control, called hull in HLSL/Slang. */
    HULL("hull"),
    /** Tessellation evaluation. */
    DOMAIN("domain"),
    /** Geometry stage. */
    GEOMETRY("geometry"),
    /** Fragment stage, also called pixel in HLSL. */
    FRAGMENT("fragment"),
    /** Compute stage for postprocessing, upscaling and frame generation. */
    COMPUTE("compute"),
    /** Mesh shader. */
    MESH("mesh"),
    /** Task/amplification shader. */
    AMPLIFICATION("amplification"),
    /** Ray generation. */
    RAY_GENERATION("raygeneration"),
    /** Ray intersection. */
    INTERSECTION("intersection"),
    /** Any hit. */
    ANY_HIT("anyhit"),
    /** Closest hit. */
    CLOSEST_HIT("closesthit"),
    /** Ray miss. */
    MISS("miss"),
    /** Callable ray shader. */
    CALLABLE("callable");

    private final String slangName;

    ShaderStage(String slangName) {
        this.slangName = slangName;
    }

    /** Name passed to slangc -stage. */
    public String slangName() {
        return slangName;
    }

    /** Stable lowercase configuration/cache key. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether this belongs to the ray tracing stages. */
    public boolean isRayTracing() {
        return this == RAY_GENERATION || this == INTERSECTION || this == ANY_HIT
                || this == CLOSEST_HIT || this == MISS || this == CALLABLE;
    }

    /** Case-insensitive parsing including Slang names such as raygeneration. */
    public static ShaderStage parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(tr("Shader stage must not be empty"));
        }
        String t = raw.trim();
        for (ShaderStage s : values()) {
            if (s.name().equalsIgnoreCase(t) || s.slangName.equalsIgnoreCase(t)) {
                return s;
            }
        }
        // Common GLSL suffix and HLSL aliases.
        return switch (t.toLowerCase(Locale.ROOT)) {
            case "vert" -> VERTEX;
            case "frag", "pixel", "ps" -> FRAGMENT;
            case "comp", "cs" -> COMPUTE;
            case "geom", "gs" -> GEOMETRY;
            case "tesc", "hs" -> HULL;
            case "tese", "ds" -> DOMAIN;
            case "rgen", "raygen" -> RAY_GENERATION;
            case "rint", "isect" -> INTERSECTION;
            case "rahit" -> ANY_HIT;
            case "rchit", "closesthit" -> CLOSEST_HIT;
            case "rmiss", "miss" -> MISS;
            case "rcall" -> CALLABLE;
            case "task", "as" -> AMPLIFICATION;
            case "ms" -> MESH;
            default -> throw new IllegalArgumentException(tr("Unknown shader stage: ") + raw);
        };
    }

    /** Infers a stage from the filename suffix, or null if unknown. */
    public static ShaderStage fromExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        try {
            return parse(fileName.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
