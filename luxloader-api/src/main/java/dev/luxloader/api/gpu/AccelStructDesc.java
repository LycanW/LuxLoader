package dev.luxloader.api.gpu;

import static dev.luxloader.api.i18n.Messages.tr;

/**
 * Acceleration structure description. triangleCount/maxVertexCount describe BLAS capacity;
 * instanceCount describes TLAS capacity. allowUpdate permits in-place updates; allowCompaction permits
 * a size query and compact copy. preferFastBuild favors dynamic geometry build time over traversal
 * quality. blasLayout describes geometry for BLAS sizing and must be null for TLAS.
 */
public record AccelStructDesc(
        String name,
        Type type,
        int triangleCount,
        int maxVertexCount,
        int instanceCount,
        boolean allowUpdate,
        boolean allowCompaction,
        boolean preferFastBuild,
        BlasLayout blasLayout) {

    /** Fields that must agree between BLAS size query and the later build. */
    public record BlasLayout(int vertexStride, AccelGeometry.IndexType indexType,
                             boolean opaque) {
        public static final BlasLayout DEFAULT =
                new BlasLayout(12, AccelGeometry.IndexType.UINT32, true);

        public BlasLayout {
            if (vertexStride < 12 || vertexStride % 4 != 0 || indexType == null) {
                throw new IllegalArgumentException("Invalid BLAS vertex/index layout");
            }
        }
    }

    public AccelStructDesc(String name, Type type, int triangleCount, int maxVertexCount,
                           int instanceCount, boolean allowUpdate, boolean allowCompaction,
                           boolean preferFastBuild) {
        this(name, type, triangleCount, maxVertexCount, instanceCount, allowUpdate,
                allowCompaction, preferFastBuild,
                type == Type.BOTTOM_LEVEL ? BlasLayout.DEFAULT : null);
    }

    /** BVH level. */
    public enum Type {
        /** Bottom level: geometry triangles or AABBs. */
        BOTTOM_LEVEL,
        /** Top level: instances referencing bottom-level structures with transforms. */
        TOP_LEVEL
    }

    public AccelStructDesc {
        if (name == null || name.isBlank()) {
            name = "accel";
        }
        if (type == null) {
            throw new IllegalArgumentException(tr("type must not be null"));
        }
        if (type == Type.BOTTOM_LEVEL && (triangleCount < 0 || maxVertexCount < 0)) {
            throw new IllegalArgumentException(tr("BLAS triangleCount/maxVertexCount must not be negative"));
        }
        if (type == Type.TOP_LEVEL && instanceCount < 0) {
            throw new IllegalArgumentException(tr("TLAS instanceCount must not be negative"));
        }
        if (type == Type.BOTTOM_LEVEL && blasLayout == null) {
            throw new IllegalArgumentException(tr("BLAS requires a geometry layout"));
        }
        if (type == Type.TOP_LEVEL && blasLayout != null) {
            throw new IllegalArgumentException(tr("TLAS does not accept a BLAS geometry layout"));
        }
    }

    /** Frequently rebuilt BLAS for animated or other dynamic geometry. */
    public static AccelStructDesc dynamicBlas(String name, int triangleCount, int maxVertexCount) {
        return dynamicBlas(name, triangleCount, maxVertexCount, BlasLayout.DEFAULT);
    }

    public static AccelStructDesc dynamicBlas(String name, int triangleCount, int maxVertexCount,
                                              BlasLayout layout) {
        return new AccelStructDesc(name, Type.BOTTOM_LEVEL, triangleCount, maxVertexCount,
                0, true, true, true, layout);
    }

    /** Static BLAS prioritizing traversal quality for terrain and other infrequently changed geometry. */
    public static AccelStructDesc staticBlas(String name, int triangleCount, int maxVertexCount) {
        return staticBlas(name, triangleCount, maxVertexCount, BlasLayout.DEFAULT);
    }

    public static AccelStructDesc staticBlas(String name, int triangleCount, int maxVertexCount,
                                             BlasLayout layout) {
        return new AccelStructDesc(name, Type.BOTTOM_LEVEL, triangleCount, maxVertexCount,
                0, false, true, false, layout);
    }

    /** Top-level instance collection. */
    public static AccelStructDesc tlas(String name, int instanceCount) {
        return new AccelStructDesc(name, Type.TOP_LEVEL, 0, 0, instanceCount,
                true, false, true, null);
    }

    /** Whether this structure's fast-build preference is supported by the available flag combination. */
    public boolean isDynamic() {
        return allowUpdate && preferFastBuild;
    }
}
