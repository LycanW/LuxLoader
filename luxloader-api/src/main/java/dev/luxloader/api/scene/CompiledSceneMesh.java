package dev.luxloader.api.scene;

import java.util.List;
import java.util.Objects;

/**
 * A copy of a host-compiled mesh, without host classes or GPU-owned handles.
 * Positions are relative to {@code origin}; UVs and colors retain the host's
 * actual vertex format. A pipeline can upload this data directly or decode it
 * for acceleration structures. A new object with the same id replaces the old
 * one; removing the id evicts it. The host publishes only accepted mesh uploads.
 */
public record CompiledSceneMesh(
        String id,
        MeshChunk.Kind kind,
        int originX,
        int originY,
        int originZ,
        int vertexStride,
        List<Attribute> attributes,
        byte[] vertices,
        byte[] indices,
        MeshChunk.IndexType indexType,
        int indexCount,
        Topology topology,
        boolean opaque) {

    public enum Topology { TRIANGLES, QUADS }

    public CompiledSceneMesh(String id, MeshChunk.Kind kind, int originX, int originY, int originZ,
                             int vertexStride, List<Attribute> attributes, byte[] vertices,
                             byte[] indices, MeshChunk.IndexType indexType, int indexCount,
                             boolean opaque) {
        this(id, kind, originX, originY, originZ, vertexStride, attributes, vertices, indices,
                indexType, indexCount, Topology.QUADS, opaque);
    }

    /** Semantic and packed format are given by the host, for example UV0/RG32_FLOAT. */
    public record Attribute(String semantic, int offset, String format) {
        public Attribute {
            if (semantic == null || semantic.isBlank() || offset < 0
                    || format == null || format.isBlank()) {
                throw new IllegalArgumentException("Invalid compiled mesh attribute");
            }
        }
    }

    public CompiledSceneMesh {
        if (id == null || id.isBlank() || vertexStride <= 0 || indexCount < 0) {
            throw new IllegalArgumentException("Invalid compiled mesh header");
        }
        kind = kind == null ? MeshChunk.Kind.OTHER : kind;
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        vertices = Objects.requireNonNull(vertices, "vertices").clone();
        indices = indices == null ? new byte[0] : indices.clone();
        indexType = indexType == null ? MeshChunk.IndexType.UNSIGNED_SHORT : indexType;
        topology = topology == null ? Topology.TRIANGLES : topology;
        if (vertices.length % vertexStride != 0) {
            throw new IllegalArgumentException("Vertex bytes do not match stride");
        }
        if (indices.length > 0 && indices.length < (long) indexCount *
                (indexType == MeshChunk.IndexType.UNSIGNED_INT ? 4 : 2)) {
            throw new IllegalArgumentException("Index bytes shorter than declared draw");
        }
    }

    public int vertexCount() {
        return vertices.length / vertexStride;
    }

    public int vertexBytes() {
        return vertices.length;
    }

    public int indexBytes() {
        return indices.length;
    }

    /** Empty means that the host uses its standard implicit quad index pattern. */
    public boolean hasExplicitIndices() {
        return indices.length > 0;
    }

    @Override
    public byte[] vertices() {
        return vertices.clone();
    }

    @Override
    public byte[] indices() {
        return indices.clone();
    }
}
