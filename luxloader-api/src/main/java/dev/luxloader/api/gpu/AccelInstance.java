package dev.luxloader.api.gpu;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * TLAS instance referencing a valid bottom-level structure and a 3x4 row-major transform (12 floats,
 * translation in the final column, nonsingular scale). Multiple instances may share a BLAS. instanceId
 * is shader-visible for material/section lookup; mask is an 8-bit visibility mask ANDed with the ray
 * cullMask. hitGroupIndex selects shaders for a ray tracing pipeline and may be zero for ray queries.
 * Nonopaque instances permit alpha tests in any-hit shaders.
 */
public record AccelInstance(
        AcceleratorHandle blas,
        float[] transform,
        int instanceId,
        int mask,
        int hitGroupIndex,
        boolean opaque) {

    public AccelInstance {
        Objects.requireNonNull(blas, "blas");
        Objects.requireNonNull(transform, "transform");
        if (transform.length != 12) {
            throw new IllegalArgumentException(
                    tr("Transform must contain 12 floats (3x4 row-major), got ") + transform.length);
        }
        // Copy defensively so later caller mutations cannot change the instance.
        transform = transform.clone();
    }

    /** Return a copy to preserve record immutability. */
    @Override
    public float[] transform() {
        return transform.clone();
    }

    /** Identity transform. */
    public static float[] identityTransform() {
        return new float[] {
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f};
    }

    /** Translation-only transform. */
    public static float[] translation(float x, float y, float z) {
        return new float[] {
                1f, 0f, 0f, x,
                0f, 1f, 0f, y,
                0f, 0f, 1f, z};
    }

    /** Default fully opaque and visible instance. */
    public static AccelInstance of(AcceleratorHandle blas, float[] transform, int instanceId) {
        return new AccelInstance(blas, transform, instanceId, 0xFF, 0, true);
    }
}
