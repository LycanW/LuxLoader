package dev.luxloader.api.scene;

import java.util.Objects;

/** A posed mesh valid for this frame, with its actual albedo binding.
 * Vertex positions use the compiled mesh origin; they are not screen-space.
 * Camera visibility includes viewing through transparent surfaces. Geometry hidden
 * from that view may still participate in reflections and illumination.
 */
public record DynamicSceneMesh(CompiledSceneMesh mesh, SceneImage albedo, float alphaCutoff, Blend blend,
                               boolean cameraVisible) {
    public enum Blend { OPAQUE, ALPHA, ADDITIVE }
    public DynamicSceneMesh(CompiledSceneMesh mesh, SceneImage albedo, float alphaCutoff, Blend blend) {
        this(mesh, albedo, alphaCutoff, blend, true);
    }
    public DynamicSceneMesh(CompiledSceneMesh mesh, SceneImage albedo, float alphaCutoff) {
        this(mesh, albedo, alphaCutoff, Blend.OPAQUE);
    }
    public DynamicSceneMesh {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(albedo, "albedo");
        Objects.requireNonNull(blend, "blend");
        if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0 || alphaCutoff > 1) {
            throw new IllegalArgumentException("Invalid alpha cutoff");
        }
    }
}
