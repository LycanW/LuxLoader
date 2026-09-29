package dev.luxloader.api.raytracing;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.gpu.AcceleratorHandle;

/**
 * CPU triangle mesh for BLAS construction. Host-managed vertex/index buffers are uploaded through
 * HostServices.
 * @param name name for diagnostics and incremental updates
 * @param positions vertexCount*3 positions in local/world coordinates according to the instance
 * transform
 * @param indices triangleCount*3 indices
 * @param uvs optional vertexCount*2 UVs
 * @param normals optional vertexCount*3 normals
 * @param opaque whether fully opaque, enabling faster builds
 * @param dynamic whether geometry changes each frame
 */
public record MeshData(
        String name,
        float[] positions,
        int[] indices,
        float[] uvs,
        float[] normals,
        boolean opaque,
        boolean dynamic) {

    public MeshData {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(tr("Mesh name must not be empty"));
        }
        if (positions == null || positions.length % 3 != 0) {
            throw new IllegalArgumentException(tr("positions length must be a multiple of 3"));
        }
        if (indices == null || indices.length % 3 != 0) {
            throw new IllegalArgumentException(tr("indices length must be a multiple of 3"));
        }
        if (uvs != null && uvs.length != positions.length / 3 * 2) {
            throw new IllegalArgumentException(tr("uvs length must equal vertex count * 2"));
        }
        if (normals != null && normals.length != positions.length) {
            throw new IllegalArgumentException(tr("normals length must equal vertex count * 3"));
        }
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    /** Compact vertex byte count for memory budgeting. */
    public long vertexBytes() {
        long b = (long) positions.length * 4 + (long) indices.length * 4;
        if (uvs != null) {
            b += (long) uvs.length * 4;
        }
        if (normals != null) {
            b += (long) normals.length * 4;
        }
        return b;
    }

    /** Whether built once; static geometry can share a larger BLAS to reduce TLAS instances. */
    public boolean isStatic() {
        return !dynamic;
    }
}
