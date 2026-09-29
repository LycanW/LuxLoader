package dev.luxloader.api.raytracing;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * Ray tracing instance (TLAS leaf).
 * @param mesh referenced MeshData name
 * @param transform 16-element row-major local-to-world matrix
 * @param materialId plugin material table index
 * @param visible whether to intersect; disabling can avoid TLAS rebuilding
 * @param castsShadow whether this instance casts shadows
 */
public record RayInstance(
        String mesh,
        float[] transform,
        int materialId,
        boolean visible,
        boolean castsShadow) {

    public RayInstance {
        Objects.requireNonNull(mesh, "mesh");
        if (transform == null || transform.length != 16) {
            throw new IllegalArgumentException(tr("transform must contain 16 floats (row-major 4x4)"));
        }
        transform = transform.clone();
    }

    /** Instance with an identity transform. */
    public static RayInstance identity(String mesh, int materialId) {
        return new RayInstance(mesh, new float[] {
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
        }, materialId, true, true);
    }

    /** Compact 12-float row-major 3x4 form for Vulkan instances. */
    public float[] transform3x4() {
        return new float[] {
                transform[0], transform[1], transform[2], transform[3],
                transform[4], transform[5], transform[6], transform[7],
                transform[8], transform[9], transform[10], transform[11]
        };
    }

    /** Inverse-transpose upper-left 3x3 normal transform for nonuniformly scaled ray hits. */
    public float[] normalTransform() {
        float[] m = transform;
        float a = m[0], b = m[1], c = m[2];
        float d = m[4], e = m[5], f = m[6];
        float g = m[8], h = m[9], i = m[10];
        float det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-12f) {
            return new float[] {1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
        }
        float inv = 1f / det;
        // Inverse-transpose 3x3.
        return new float[] {
                (e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv,
                (f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
                (d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv
        };
    }

    public RayInstance withVisible(boolean v) {
        return new RayInstance(mesh, transform, materialId, v, castsShadow);
    }

    public RayInstance withTransform(float[] t) {
        return new RayInstance(mesh, t, materialId, visible, castsShadow);
    }
}
