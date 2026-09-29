package dev.luxloader.api.gpu;

import java.util.List;
import java.util.Objects;

/**
 * Portable raster pipeline state. Shader bytecode is supplied separately when
 * the pipeline is created. A zero vertex stride uses shader-generated vertices.
 */
public record GraphicsPipelineDesc(
        String name,
        String vertexEntry,
        String fragmentEntry,
        GpuFormat colorFormat,
        GpuFormat depthFormat,
        int vertexStride,
        List<VertexAttribute> attributes,
        boolean depthTest,
        boolean depthWrite,
        DepthCompare depthCompare,
        boolean alphaBlend,
        CullMode cullMode,
        int pushConstants,
        List<ComputePipelineDesc.Binding> bindings) {

    public enum CullMode { NONE, BACK, FRONT }
    public enum DepthCompare { LESS_OR_EQUAL, GREATER_OR_EQUAL }

    /** An attribute within the single interleaved vertex buffer. */
    public record VertexAttribute(int location, GpuFormat format, int offset) {
        public VertexAttribute {
            if (location < 0 || offset < 0 || format == null || !format.isDefined()
                    || format.isDepth()) {
                throw new IllegalArgumentException("Invalid vertex attribute");
            }
        }
    }

    public GraphicsPipelineDesc(String name, String vertexEntry, String fragmentEntry,
                                GpuFormat colorFormat, GpuFormat depthFormat, int vertexStride,
                                List<VertexAttribute> attributes, boolean depthTest,
                                boolean depthWrite, DepthCompare depthCompare,
                                boolean alphaBlend, CullMode cullMode, int pushConstants) {
        this(name, vertexEntry, fragmentEntry, colorFormat, depthFormat, vertexStride,
                attributes, depthTest, depthWrite, depthCompare, alphaBlend, cullMode,
                pushConstants, List.of());
    }

    public GraphicsPipelineDesc {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(colorFormat, "colorFormat");
        if (name.isBlank() || !colorFormat.isDefined() || colorFormat.isDepth()) {
            throw new IllegalArgumentException("A graphics pipeline needs a name and color format");
        }
        vertexEntry = vertexEntry == null || vertexEntry.isBlank() ? "main" : vertexEntry;
        fragmentEntry = fragmentEntry == null || fragmentEntry.isBlank() ? "main" : fragmentEntry;
        depthFormat = depthFormat == null ? GpuFormat.UNDEFINED : depthFormat;
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        cullMode = cullMode == null ? CullMode.BACK : cullMode;
        depthCompare = depthCompare == null ? DepthCompare.LESS_OR_EQUAL : depthCompare;
        if (vertexStride < 0 || pushConstants < 0 || pushConstants > 128
                || (vertexStride == 0 && !attributes.isEmpty())
                || (depthFormat == GpuFormat.UNDEFINED && (depthTest || depthWrite))) {
            throw new IllegalArgumentException("Invalid graphics pipeline layout: " + name);
        }
        for (VertexAttribute attribute : attributes) {
            if (attribute.offset() + attribute.format().bytesPerPixel() > vertexStride) {
                throw new IllegalArgumentException("Vertex attribute exceeds stride: " + name);
            }
        }
        for (ComputePipelineDesc.Binding binding : bindings) {
            if ((binding.count() != 1 && binding.type() != ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE_ARRAY)
                    || binding.type() == ComputePipelineDesc.DescriptorType.ACCELERATION_STRUCTURE
                    || binding.type() == ComputePipelineDesc.DescriptorType.STORAGE_IMAGE) {
                throw new IllegalArgumentException("Unsupported raster descriptor: " + binding);
            }
        }
    }
}
