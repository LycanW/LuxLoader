package dev.luxloader.api.pipeline;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Locale;
import java.util.Objects;

/**
 * Semantic pass category for validation, conflict arbitration, performance attribution and UI. It does
 * not determine execution order. The compiler uses reads/writes, explicit dependencies and
 * write-after-write serialization. Connect passes with named plugin resources to express the required
 * data flow; host-internal intermediate buffers are outside this graph.
 */
public enum StageKind {

    /** Geometry and lighting scene rendering, including custom rasterization. */
    SCENE_RASTER,
    /** Shadow map generation. */
    SHADOW,
    /** Screen-space ambient occlusion. */
    SSAO,
    /** Global illumination, including ray tracing, probes or voxel methods. */
    GI,
    /** Deferred shading. */
    DEFERRED_LIGHTING,
    /** Volumetric lighting or fog. */
    VOLUMETRICS,
    /** Particles and transparency. */
    TRANSLUCENT,
    /** Color grading and tone mapping. */
    TONEMAP,
    /** Bloom. */
    BLOOM,
    /** Antialiasing without upscaling. */
    ANTI_ALIASING,
    /** Temporal super resolution. */
    SUPER_RESOLUTION,
    /** Single-frame spatial upscaling and sharpening. */
    SPATIAL_UPSCALE,
    /** Frame generation between rendered frames. */
    FRAME_GENERATION,
    /** Ray tracing reprojection or denoising. */
    RAYTRACED_DENOISE,
    /** Path tracing. */
    RAY_TRACING,
    /** Asynchronous spacewarp. */
    ASYNC_REPROJECTION,
    /** UI/HUD composition. */
    UI_COMPOSITE,
    /** Custom pixel-level postprocessing. */
    CUSTOM_POST,
    /** Final pre-presentation processing: sharpening, dithering or color-space conversion. */
    FINAL_PRESENT,
    /** Utility pass such as upsampling or downsampling. */
    RESAMPLE,
    /** Other or unclassified. */
    OTHER;

    /** Stable lowercase configuration identifier, e.g. kind = "super_resolution". */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse a case-insensitive configuration value; throw when invalid. */
    public static StageKind parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        for (StageKind k : values()) {
            if (k.name().equalsIgnoreCase(raw)) {
                return k;
            }
        }
        throw new IllegalArgumentException(tr("Unknown pass kind: ") + raw);
    }

    /** Parse or return OTHER on failure. */
    public static StageKind tryParse(String raw) {
        if (raw == null) {
            return OTHER;
        }
        for (StageKind k : values()) {
            if (k.name().equalsIgnoreCase(raw.trim())) {
                return k;
            }
        }
        return OTHER;
    }

    /** Whether in the super-resolution family, for active-feature reporting. */
    public boolean isUpscaling() {
        return this == SUPER_RESOLUTION || this == SPATIAL_UPSCALE;
    }

    /** Whether in the ray tracing family. */
    public boolean isRayTracing() {
        return this == RAY_TRACING || this == RAYTRACED_DENOISE;
    }
}
