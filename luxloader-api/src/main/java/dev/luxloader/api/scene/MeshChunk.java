package dev.luxloader.api.scene;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.gpu.ImageHandle;

/**
 * Extracted geometry chunk with GPU buffer references and metadata. The kernel describes where data
 * lives without interpreting positions or indices. Chunk boundaries retain spatial structure for
 * plugin frustum culling, LOD and indirect draws. The host owns the actual vertex/index buffers;
 * plugins bind their handles through GpuCommands or native APIs.
 */
public record MeshChunk(
        String name,
        Kind kind,
        long vertexBuffer,
        long indexBuffer,
        int vertexCount,
        int indexCount,
        int vertexStride,
        IndexType indexType,
        float[] boundsMin,
        float[] boundsMax,
        long materialMask,
        boolean opaque,
        boolean dynamic) {

    /** Geometry category used for pipeline selection. */
    public enum Kind {
        /** Opaque terrain. */
        TERRAIN_OPAQUE,
        /** Water/translucent terrain. */
        TERRAIN_TRANSLUCENT,
        /** Vegetation or other blocks needing special animation/shading. */
        TERRAIN_FOLIAGE,
        /** Entity models. */
        ENTITY,
        /** Block entities such as chests and signs. */
        BLOCK_ENTITY,
        /** Particles. */
        PARTICLE,
        /** Skybox or sky dome. */
        SKY,
        /** Clouds. */
        CLOUDS,
        /** Rain and snow. */
        WEATHER,
        /** Held items. */
        HAND,
        /** Other geometry. */
        OTHER
    }

    /** Index type. */
    public enum IndexType {
        NONE(0),
        UNSIGNED_SHORT(2),
        UNSIGNED_INT(4);

        private final int byteSize;

        IndexType(int byteSize) {
            this.byteSize = byteSize;
        }

        public int byteSize() {
            return byteSize;
        }
    }

    public MeshChunk {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(tr("Mesh name must not be empty"));
        }
        kind = kind == null ? Kind.OTHER : kind;
        indexType = indexType == null ? IndexType.NONE : indexType;
        if (boundsMin == null || boundsMin.length != 3) {
            boundsMin = new float[] {0f, 0f, 0f};
        }
        if (boundsMax == null || boundsMax.length != 3) {
            boundsMax = new float[] {0f, 0f, 0f};
        }
        if (vertexCount < 0 || indexCount < 0) {
            throw new IllegalArgumentException(tr("Vertex/index counts must not be negative"));
        }
    }

    /** Whether a valid vertex buffer exists. */
    public boolean hasGeometry() {
        return vertexCount > 0 && vertexBuffer != 0L;
    }

    /** Index byte count. */
    public long indexBytes() {
        return (long) indexCount * indexType.byteSize();
    }

    /** Vertex byte count excluding indices. */
    public long vertexBytes() {
        return (long) vertexCount * Math.max(1, vertexStride);
    }

    /** Bounding box center. */
    public float[] center() {
        return new float[] {
                (boundsMin[0] + boundsMax[0]) * 0.5f,
                (boundsMin[1] + boundsMax[1]) * 0.5f,
                (boundsMin[2] + boundsMax[2]) * 0.5f
        };
    }

    /** Bounding sphere radius for frustum culling. */
    public float boundingRadius() {
        float dx = (boundsMax[0] - boundsMin[0]) * 0.5f;
        float dy = (boundsMax[1] - boundsMin[1]) * 0.5f;
        float dz = (boundsMax[2] - boundsMin[2]) * 0.5f;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Diagnostic summary. */
    public String describe() {
        return name + " [" + kind + "] v=" + vertexCount + " i=" + indexCount
                + (dynamic ? " dynamic" : "") + (opaque ? "" : " translucent");
    }

    /** Empty geometry for a category with no content this frame. */
    public static MeshChunk empty(String name, Kind kind) {
        return new MeshChunk(name, kind, 0L, 0L, 0, 0, 0, IndexType.NONE,
                null, null, 0L, true, false);
    }
}
