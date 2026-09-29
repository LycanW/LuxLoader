package dev.luxloader.api.raytracing;

import dev.luxloader.api.frame.CameraParams;
import dev.luxloader.api.gpu.ImageHandle;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete ray tracing scene state used to update TLAS and dispatch shaders.
 * @param camera camera
 * @param meshes intersectable meshes by name
 * @param instances scene instances
 * @param frameIndex frame index for incremental updates
 * @param rebuiltGeometry whether geometry needs rebuilding after chunk/block changes
 * @param materialTable materials by instance materialId
 * @param skyColor RGB sky/environment color for misses
 * @param sunDirection world-space unit direction
 * @param sunColor RGB sun color and alpha intensity
 * @param textures optional G-buffer inputs such as albedo, normal, depth and roughness
 */
public record RayTracingScene(
        CameraParams camera,
        Map<String, MeshData> meshes,
        List<RayInstance> instances,
        long frameIndex,
        boolean rebuiltGeometry,
        Map<Integer, Material> materialTable,
        float[] skyColor,
        float[] sunDirection,
        float[] sunColor,
        Map<String, ImageHandle> textures) {

    /**
     * Ray tracing material.
     * @param id material ID
     * @param albedo RGB reflectance
     * @param roughness roughness in [0,1]
     * @param metallic metallic factor in [0,1]
     * @param emissive RGB emission
     * @param ior refractive index, e.g. water 1.33 or glass 1.5
     * @param alpha opacity
     * @param doubleSided whether both sides are visible
     */
    public record Material(
            int id,
            float[] albedo,
            float roughness,
            float metallic,
            float[] emissive,
            float ior,
            float alpha,
            boolean doubleSided) {

        public Material {
            albedo = albedo == null ? new float[] {0.8f, 0.8f, 0.8f} : albedo.clone();
            emissive = emissive == null ? new float[] {0f, 0f, 0f} : emissive.clone();
            roughness = clamp01(roughness);
            metallic = clamp01(metallic);
            alpha = clamp01(alpha);
            if (ior <= 0f) {
                ior = 1.5f;
            }
        }

        private static float clamp01(float v) {
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        }

        /** Approximate opacity threshold; lower alpha requires alpha testing or blending. */
        public boolean isOpaque() {
            return alpha >= 0.999f;
        }

        /** Whether the material emits light. */
        public boolean isEmissive() {
            return emissive[0] > 0f || emissive[1] > 0f || emissive[2] > 0f;
        }

        /** Whether the material is a smooth metal (low roughness, high metallic factor). */
        public boolean isMirror() {
            return metallic > 0.5f && roughness < 0.15f;
        }
    }

    public RayTracingScene {
        camera = camera == null ? CameraParams.identity() : camera;
        meshes = meshes == null ? Map.of() : Map.copyOf(meshes);
        instances = instances == null ? List.of() : List.copyOf(instances);
        materialTable = materialTable == null ? Map.of() : Map.copyOf(materialTable);
        skyColor = skyColor == null ? new float[] {0.5f, 0.7f, 1.0f} : skyColor.clone();
        sunDirection = sunDirection == null ? new float[] {0f, 1f, 0f} : sunDirection.clone();
        sunColor = sunColor == null ? new float[] {1f, 1f, 1f, 1f} : sunColor.clone();
        textures = textures == null ? Map.of() : Map.copyOf(textures);
        Objects.requireNonNull(camera, "camera");
    }

    /** Scene triangle count for performance budgets and diagnostics. */
    public long triangleCount() {
        long n = 0;
        for (MeshData m : meshes.values()) {
            n += m.triangleCount();
        }
        return n;
    }

    /** Total vertex byte count. */
    public long geometryBytes() {
        long b = 0;
        for (MeshData m : meshes.values()) {
            b += m.vertexBytes();
        }
        return b;
    }

    /** Visible instance count. */
    public long visibleInstanceCount() {
        return instances.stream().filter(RayInstance::visible).count();
    }

    /** Empty scene for menus/loading screens. */
    public static RayTracingScene empty() {
        return new RayTracingScene(CameraParams.identity(), Map.of(), List.of(), 0L, false,
                Map.of(), null, null, null, Map.of());
    }

    /** Whether geometry and instances are sufficient for ray tracing. */
    public boolean isRenderable() {
        return !instances.isEmpty() && !meshes.isEmpty();
    }
}
