package dev.luxloader.api.scene;

/**
 * Physical material description interpreted by plugins from generic host data such as fluid
 * properties, tags and atlas sprites. VoxelMesher's material component indexes
 * SceneSnapshot.materials, separating shading changes from geometry rebuilds. Albedo is linear RGB
 * reflectance; roughness spans smooth to rough microfacets; metallic interpolates dielectric/conductor
 * behavior, with conductor albedo as F0; emissive is linear RGB radiance; ior is refractive index
 * (water 1.33, ice 1.31, glass 1.52, diamond 2.42). Transmission controls through-material light
 * independently of alpha coverage/cutouts. Arrays simplify float4 packing and are defensively copied.
 */
public record MaterialDef(
        String name,
        float[] albedo,
        float roughness,
        float metallic,
        float[] emissive,
        float ior,
        float transmission,
        float alpha,
        float[] uv,
        float effect,
        TextureRef texture) {

    /** Floats per shader material: four float4 values (16 floats). */
    public static final int FLOATS_PER_MATERIAL = 16;

    /** Zero area means there is no atlas sprite (fluids and synthetic materials). */
    private static final float[] NO_UV = {0f, 0f, 0f, 0f};

    /** Fallback material: neutral gray, opaque and diffuse. */
    public static final MaterialDef DEFAULT = new MaterialDef(
            "default", new float[] {0.8f, 0.8f, 0.8f}, 0.9f, 0f,
            new float[] {0f, 0f, 0f}, 1.5f, 0f, 1f, null);

    public MaterialDef {
        name = name == null || name.isBlank() ? "unnamed" : name;
        albedo = albedo == null || albedo.length != 3
                ? new float[] {0.8f, 0.8f, 0.8f} : albedo.clone();
        emissive = emissive == null || emissive.length != 3
                ? new float[] {0f, 0f, 0f} : emissive.clone();
        uv = uv == null || uv.length != 4 ? NO_UV.clone() : uv.clone();
        roughness = clamp01(roughness);
        metallic = clamp01(metallic);
        transmission = clamp01(transmission);
        alpha = clamp01(alpha);
        effect = clamp01(effect);
        if (ior < 1f) {
            // This material model requires a refractive index of at least 1.
            ior = 1.5f;
        }
    }

    /** Compatibility constructor without an atlas rectangle; no atlas sampling is performed. */
    public MaterialDef(String name, float[] albedo, float roughness, float metallic,
                       float[] emissive, float ior, float transmission, float alpha) {
        this(name, albedo, roughness, metallic, emissive, ior, transmission, alpha, null, 0f);
    }

    /** Compatibility constructor without special effects. */
    public MaterialDef(String name, float[] albedo, float roughness, float metallic,
                       float[] emissive, float ior, float transmission, float alpha,
                       float[] uv) {
        this(name, albedo, roughness, metallic, emissive, ior, transmission, alpha, uv, 0f);
    }

    /** Compatibility constructor without a source sprite reference. */
    public MaterialDef(String name, float[] albedo, float roughness, float metallic,
                       float[] emissive, float ior, float transmission, float alpha,
                       float[] uv, float effect) {
        this(name, albedo, roughness, metallic, emissive, ior, transmission, alpha,
                uv, effect, null);
    }

    /** Copies the material with an atlas UV rectangle. */
    public MaterialDef withUv(float u0, float v0, float u1, float v1) {
        return new MaterialDef(name, albedo, roughness, metallic, emissive, ior,
                transmission, alpha, new float[] {u0, v0, u1, v1}, effect, texture);
    }

    /** Attaches a generic resource-pack sprite source for plugin interpretation. */
    public MaterialDef withTexture(TextureRef value) {
        return new MaterialDef(name, albedo, roughness, metallic, emissive, ior,
                transmission, alpha, uv, effect, value);
    }

    /** Host-supplied effect flags; ordinary resource-pack materials use zero. */
    public MaterialDef withEffect(float value) {
        return new MaterialDef(name, albedo, roughness, metallic, emissive, ior,
                transmission, alpha, uv, value, texture);
    }

    /**
     * Copies the material with a different albedo, preserving UVs and physical properties. Minecraft
     * applies biome tint only to faces with tintindex: grass side dirt is untinted while its overlay is
     * tinted. Keep separate tinted/untinted entries for the same sprite; white albedo preserves untinted
     * texels.
     */
    public MaterialDef withAlbedo(float r, float g, float b) {
        return new MaterialDef(name, new float[] {r, g, b}, roughness, metallic, emissive,
                ior, transmission, alpha, uv, effect, texture);
    }

    /** Internal read-only access. */
    public float[] uvRaw() {
        return uv;
    }

    @Override
    public float[] uv() {
        return uv.clone();
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) {
            return 0f;
        }
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    @Override
    public float[] albedo() {
        return albedo.clone();
    }

    @Override
    public float[] emissive() {
        return emissive.clone();
    }

    /** Internal read-only access for per-frame packing without cloning. */
    public float[] albedoRaw() {
        return albedo;
    }

    /** Internal read-only access. */
    public float[] emissiveRaw() {
        return emissive;
    }

    /** Whether light transmits through the material. */
    public boolean isTransmissive() {
        return transmission > 0.01f;
    }

    /** Whether the material emits light. */
    public boolean isEmissive() {
        return emissive[0] > 0f || emissive[1] > 0f || emissive[2] > 0f;
    }

    /**
     * Packs 16 floats using float4 alignment to avoid std430 float3 padding: [0..3] albedo.rgb/roughness;
     * [4..7] emissive.rgb/metallic; [8..11] ior/transmission/alpha/effect; [12..15] UV rectangle. This is
     * the shared layout contract for plugins and shaders.
     */
    public void writeTo(float[] out, int offset) {
        out[offset] = albedo[0];
        out[offset + 1] = albedo[1];
        out[offset + 2] = albedo[2];
        out[offset + 3] = roughness;
        out[offset + 4] = emissive[0];
        out[offset + 5] = emissive[1];
        out[offset + 6] = emissive[2];
        out[offset + 7] = metallic;
        out[offset + 8] = ior;
        out[offset + 9] = transmission;
        out[offset + 10] = alpha;
        out[offset + 11] = effect;
        out[offset + 12] = uv[0];
        out[offset + 13] = uv[1];
        out[offset + 14] = uv[2];
        out[offset + 15] = uv[3];
    }

    public static float[] pack(java.util.List<MaterialDef> materials) {
        java.util.Objects.requireNonNull(materials, "materials");
        float[] out = new float[materials.size() * FLOATS_PER_MATERIAL];
        for (int i = 0; i < materials.size(); i++) {
            materials.get(i).writeTo(out, i * FLOATS_PER_MATERIAL);
        }
        return out;
    }
}
