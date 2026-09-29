package dev.luxloader.api.frame;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * Camera parameters for projection and temporal reconstruction. Matrices contain 16 floats in
 * row-major order; transpose JOML Matrix4f.get(float[]) column-major output at the adapter boundary.
 * view transforms world to camera; projection transforms camera to clip; projectionInverse supports
 * reprojection. Jitter and previous jitter are in render-resolution pixels. nearPlane/farPlane define
 * depth range, isOrthographic selects projection type, and fovYRadians is ignored for orthographic
 * cameras.
 */
public record CameraParams(
        float[] view,
        float[] projection,
        float[] projectionInverse,
        float jitterX,
        float jitterY,
        float prevJitterX,
        float prevJitterY,
        float nearPlane,
        float farPlane,
        boolean isOrthographic,
        float fovYRadians) {

    private static final float[] IDENTITY = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
    };

    public CameraParams {
        view = view == null ? IDENTITY.clone() : view.clone();
        projection = projection == null ? IDENTITY.clone() : projection.clone();
        projectionInverse = projectionInverse == null ? IDENTITY.clone() : projectionInverse.clone();
        check16(view, "view");
        check16(projection, "projection");
        check16(projectionInverse, "projectionInverse");
    }

    private static void check16(float[] m, String name) {
        if (m.length != 16) {
            throw new IllegalArgumentException(name + tr(" must contain 16 floats (row-major 4x4 matrix)"));
        }
    }

    /** Placeholder for screen-space effects without camera information. */
    public static CameraParams identity() {
        return new CameraParams(IDENTITY, IDENTITY, IDENTITY, 0f, 0f, 0f, 0f, 0.05f, 1000f, false, 1.2217f);
    }

    public float[] view() {
        return view.clone();
    }

    public float[] projection() {
        return projection.clone();
    }

    public float[] projectionInverse() {
        return projectionInverse.clone();
    }

    /** Direct internal array access for read-only performance-sensitive paths; avoids per-frame copies. */
    public float[] projectionRaw() {
        return projection;
    }

    public float[] viewRaw() {
        return view;
    }

    /** Whether jitter is nonzero. */
    public boolean hasJitter() {
        return jitterX != 0f || jitterY != 0f;
    }

    /** False for the identity placeholder used before a game camera is available. */
    public boolean hasProjection() {
        return !java.util.Arrays.equals(projection, IDENTITY);
    }

    public boolean hasProjectionInverse() {
        return hasProjection() && !java.util.Arrays.equals(projectionInverse, IDENTITY);
    }

    /** World-space camera position, derived from the orthonormal view transform. */
    public float[] worldPosition() {
        float tx = view[3], ty = view[7], tz = view[11];
        return new float[] {
                -(view[0] * tx + view[4] * ty + view[8] * tz),
                -(view[1] * tx + view[5] * ty + view[9] * tz),
                -(view[2] * tx + view[6] * ty + view[10] * tz)
        };
    }

    /** Whether the camera is moving, for dynamic resolution and ghosting control. */
    public boolean movedFrom(CameraParams previous, float epsilon) {
        Objects.requireNonNull(previous, "previous");
        return Math.abs(jitterX - previous.jitterX) > epsilon
                || Math.abs(jitterY - previous.jitterY) > epsilon
                || !java.util.Arrays.equals(view, previous.view);
    }

    /** Frustum depth range. */
    public float depthRange() {
        return farPlane - nearPlane;
    }
}
