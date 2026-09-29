package dev.luxloader.api.scene;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * CPU vertices and indices before upload. The adapter extracts game-specific geometry; the kernel owns
 * GPU allocation/upload and produces MeshChunk handles. This boundary keeps the adapter independent of
 * memory allocation and the kernel independent of game internals. Vertices are interleaved floats,
 * with stride measured in bytes to match Vulkan/AccelGeometry.
 * @param name debug name
 * @param kind geometry category for pipeline selection
 * @param vertices interleaved data with length divisible by vertexStride/4
 * @param indices triangle-list indices, length divisible by 3
 * @param vertexStride byte stride, divisible by 4
 * @param indexType index width
 * @param boundsMin minimum world-space bounds
 * @param boundsMax maximum world-space bounds
 * @param opaque whether eligible for opaque batches
 */
public record CpuMesh(
        String name,
        MeshChunk.Kind kind,
        float[] vertices,
        int[] indices,
        int vertexStride,
        MeshChunk.IndexType indexType,
        float[] boundsMin,
        float[] boundsMax,
        boolean opaque) {

    public CpuMesh {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(tr("Mesh name must not be empty"));
        }
        Objects.requireNonNull(vertices, "vertices");
        Objects.requireNonNull(indices, "indices");
        kind = kind == null ? MeshChunk.Kind.OTHER : kind;
        indexType = indexType == null ? MeshChunk.IndexType.UNSIGNED_INT : indexType;

        if (vertexStride <= 0 || vertexStride % 4 != 0) {
            throw new IllegalArgumentException(
                    tr("vertexStride must be a positive multiple of 4 (float alignment), got ") + vertexStride);
        }
        if (vertices.length * 4 % vertexStride != 0) {
            throw new IllegalArgumentException(tr("Vertex data length ") + vertices.length
                    + tr(" floats is not a multiple of stride ") + vertexStride + tr("CpuMesh.431a2a7bf0", " bytes"));
        }
        if (indices.length % 3 != 0) {
            throw new IllegalArgumentException(tr("Index count must be a multiple of 3, got ") + indices.length);
        }
        // Reject out-of-range indices on the CPU: GPU reads can otherwise produce corrupt geometry without an actionable error.
        int vertexCount = vertices.length * 4 / vertexStride;
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException(tr("Index ") + index + tr(" is out of range (vertex count ") + vertexCount
                        + tr("); GPU access would read undefined vertices without reporting an error"));
            }
        }
        if (boundsMin == null || boundsMin.length != 3) {
            boundsMin = new float[] {0f, 0f, 0f};
        }
        if (boundsMax == null || boundsMax.length != 3) {
            boundsMax = new float[] {0f, 0f, 0f};
        }
    }

    /** Vertex count. */
    public int vertexCount() {
        return vertices.length * 4 / vertexStride;
    }

    /** Index count. */
    public int indexCount() {
        return indices.length;
    }

    /** Triangle count. */
    public int triangleCount() {
        return indices.length / 3;
    }

    /** Vertex byte count. */
    public long vertexBytes() {
        return (long) vertices.length * 4L;
    }

    /** Index byte count. */
    public long indexBytes() {
        return (long) indices.length * 4L;
    }

    public boolean isEmpty() {
        return indices.length == 0;
    }

    /** Constructs from VoxelMesher output. */
    public static CpuMesh of(String name, MeshChunk.Kind kind, VoxelMesher.Mesh mesh) {
        return new CpuMesh(name, kind, mesh.vertices(), mesh.indices(),
                VoxelMesher.VERTEX_STRIDE, MeshChunk.IndexType.UNSIGNED_INT,
                mesh.boundsMin(), mesh.boundsMax(), true);
    }
}
