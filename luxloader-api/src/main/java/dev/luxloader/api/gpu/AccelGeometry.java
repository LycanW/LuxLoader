package dev.luxloader.api.gpu;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * Triangle geometry for a BLAS. Buffer handles refer to caller-populated device buffers and offsets
 * are bytes; this descriptor never uploads data. Each vertex starts with float3 position, with stride
 * at least 12 (zero normalizes to 12). A null index buffer selects non-indexed triangles; index counts
 * must be multiples of three. Opaque geometry skips candidate alpha tests, while masked materials
 * require opaque=false.
 */
public record AccelGeometry(
        GpuDevice.Handle vertexBuffer,
        long vertexOffset,
        int vertexCount,
        int vertexStride,
        GpuDevice.Handle indexBuffer,
        long indexOffset,
        int indexCount,
        IndexType indexType,
        boolean opaque) {

    public AccelGeometry(GpuDevice.Handle vertexBuffer, long vertexOffset, int vertexCount,
                         int vertexStride, GpuDevice.Handle indexBuffer, long indexOffset,
                         int indexCount, IndexType indexType) {
        this(vertexBuffer, vertexOffset, vertexCount, vertexStride,
                indexBuffer, indexOffset, indexCount, indexType, true);
    }

    /** Index width; ray tracing geometry does not support 8-bit indices. */
    public enum IndexType {
        /** Unsigned 16-bit indices. */
        UINT16(2),
        /** Unsigned 32-bit indices. */
        UINT32(4);

        private final int bytes;

        IndexType(int bytes) {
            this.bytes = bytes;
        }

        /** Bytes per index. */
        public int bytes() {
            return bytes;
        }
    }

    public AccelGeometry {
        Objects.requireNonNull(vertexBuffer, "vertexBuffer");
        Objects.requireNonNull(indexBuffer, "indexBuffer");
        Objects.requireNonNull(indexType, "indexType");
        if (vertexCount <= 0) {
            throw new IllegalArgumentException(tr("vertexCount must be positive, got ") + vertexCount);
        }
        // Normalize the tightly packed float3 stride to 12 for consistent downstream handling.
        vertexStride = vertexStride == 0 ? 12 : vertexStride;
        if (vertexStride < 12) {
            throw new IllegalArgumentException(
                    tr("vertexStride must be at least 12 (float3 position), got ") + vertexStride);
        }
        if (indexCount <= 0) {
            throw new IllegalArgumentException(tr("indexCount must be positive, got ") + indexCount);
        }
        if (indexCount % 3 != 0) {
            throw new IllegalArgumentException(tr("indexCount must be a multiple of 3, got ") + indexCount);
        }
        if (vertexOffset < 0 || indexOffset < 0) {
            throw new IllegalArgumentException(tr("Offsets must not be negative"));
        }
    }

    /** Triangle count. */
    public int triangleCount() {
        return indexCount / 3;
    }

    /** Whether the build uses indices. */
    public static AccelGeometry indexed(GpuDevice.Handle vertices, int vertexCount, int vertexStride,
                                        GpuDevice.Handle indices, int indexCount, IndexType type) {
        return new AccelGeometry(vertices, 0L, vertexCount, vertexStride,
                indices, 0L, indexCount, type);
    }

    public AccelGeometry withOpaque(boolean value) {
        return new AccelGeometry(vertexBuffer, vertexOffset, vertexCount, vertexStride,
                indexBuffer, indexOffset, indexCount, indexType, value);
    }
}
