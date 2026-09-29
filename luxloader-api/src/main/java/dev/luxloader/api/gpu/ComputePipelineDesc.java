package dev.luxloader.api.gpu;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * Pipeline/shader stage description.
 * @param name name, also used for the shader file (see {@code shaderPath})
 * @param shaderPath classpath resource, e.g. {@code /luxloader/shaders/exp/upscale.comp.spv}
 * @param stage shader stage
 * @param pushConstants push constant size in bytes; zero disables them
 * @param bindings descriptor layout
 * @param workgroupX compute workgroup size X
 * @param workgroupY compute workgroup size Y
 * @param workgroupZ compute workgroup size Z
 * @param entryPoint entry point, usually {@code main}
 */
public record ComputePipelineDesc(
        String name,
        String shaderPath,
        ShaderStage stage,
        int pushConstants,
        java.util.List<Binding> bindings,
        int workgroupX,
        int workgroupY,
        int workgroupZ,
        String entryPoint) {

    /** Shader stage. */
    public enum ShaderStage {
        /** Compute shader for postprocessing, upscaling or frame generation. */
        COMPUTE,
        /** Vertex and fragment shaders for custom raster pipelines. */
        VERTEX_FRAGMENT,
        /** Ray generation. */
        RAYGEN,
        /** Intersection testing. */
        INTERSECTION,
        /** Any-hit shader. */
        ANY_HIT,
        /** Closest-hit shader. */
        CLOSEST_HIT,
        /** Miss shader. */
        MISS
    }

    /** Descriptor type. */
    public enum DescriptorType {
        SAMPLED_IMAGE,
        STORAGE_IMAGE,
        UNIFORM_BUFFER,
        STORAGE_BUFFER,
        /** Read-only texture array for bindless access. */
        SAMPLED_IMAGE_ARRAY,
        /** Acceleration structure. */
        ACCELERATION_STRUCTURE,
        /**
         * Combined image sampler ({@code sampler2D} in GLSL). Unlike {@link #SAMPLED_IMAGE}, which represents
         * a {@code texture2D} with a separate sampler, this descriptor combines the image and sampling state.
         * Bind {@code uniform sampler2D tex} using this type.
         */
        COMBINED_IMAGE_SAMPLER
    }

    /**
     * Descriptor binding. <p>Every declared binding must be written before dispatch. Bindings are fixed
     * and cannot be optional: the loader builds the layout from this list and rejects dispatch with the
     * missing binding's name. Reading an unwritten descriptor is undefined behavior ({@code
     * VUID-vkCmdDispatch-None-08114}). <p>Only declare resources available for this frame. The current
     * Minecraft integration does not provide {@code FrameGraph.DEPTH} or {@code FrameGraph.MOTION}; use
     * separate pipelines or placeholder textures for fallback paths. <p>Arrays ({@code count > 1}) are
     * unsupported: the name-based API binds one image and cannot populate later array elements. Such
     * declarations are rejected before dispatch. Bindless support requires an API for multiple images per
     * name.
     * @param binding shader binding number ({@code layout(binding = N)})
     * @param type descriptor type
     * @param count array size; only 1 is supported
     * @param name semantic name for automatic frame resource matching, e.g. {@code color}, {@code depth},
     * {@code motion} or {@code output}
     */
    public record Binding(int binding, DescriptorType type, int count, String name) {
        public Binding {
            Objects.requireNonNull(type, "type");
            if (binding < 0) {
                throw new IllegalArgumentException(tr("binding must not be negative"));
            }
            if (count < 1) {
                throw new IllegalArgumentException(tr("count must be at least 1"));
            }
            name = name == null ? "" : name;
        }

        public static Binding sampled(int binding, String name) {
            return new Binding(binding, DescriptorType.SAMPLED_IMAGE, 1, name);
        }

        /**
         * Combined image sampler binding, corresponding to GLSL sampler2D. Use this for compute texture
         * sampling instead of sampled().
         */
        public static Binding sampler(int binding, String name) {
            return new Binding(binding, DescriptorType.COMBINED_IMAGE_SAMPLER, 1, name);
        }

        public static Binding storage(int binding, String name) {
            return new Binding(binding, DescriptorType.STORAGE_IMAGE, 1, name);
        }

        public static Binding uniform(int binding, String name) {
            return new Binding(binding, DescriptorType.UNIFORM_BUFFER, 1, name);
        }
    }

    public ComputePipelineDesc {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(shaderPath, "shaderPath");
        stage = stage == null ? ShaderStage.COMPUTE : stage;
        bindings = bindings == null ? java.util.List.of() : java.util.List.copyOf(bindings);
        entryPoint = entryPoint == null ? "main" : entryPoint;
        if (pushConstants < 0) {
            throw new IllegalArgumentException(tr("pushConstants must not be negative"));
        }
        if (stage == ShaderStage.COMPUTE && (workgroupX < 1 || workgroupY < 1 || workgroupZ < 1)) {
            throw new IllegalArgumentException(tr("Compute workgroup dimensions must be at least 1"));
        }
    }

    /**
     * Derives dispatch counts from the output and workgroup dimensions.
     * @param outputW output width
     * @param outputH output height
     * @return three elements: {@code [gx, gy, gz]}
     */
    public int[] dispatchGroups(int outputW, int outputH) {
        int gx = (outputW + workgroupX - 1) / workgroupX;
        int gy = (outputH + workgroupY - 1) / workgroupY;
        return new int[] {gx, gy, workgroupZ};
    }

    /** Compute pipeline with one storage-image output. */
    public static ComputePipelineDesc compute(String name, String shaderPath, int pushConstants,
                                              java.util.List<Binding> bindings, int wgX, int wgY) {
        return new ComputePipelineDesc(name, shaderPath, ShaderStage.COMPUTE, pushConstants, bindings, wgX, wgY, 1, "main");
    }
}
