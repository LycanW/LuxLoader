package dev.luxloader.api.gpu;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Buffer description with a debug name, byte size, usage flags and optional CPU mapping requirement
 * for uniforms or readback.
 */
public record BufferDesc(String name, long size, EnumSet<Usage> usage, boolean hostVisible) {

    /** Buffer usage flags. */
    public enum Usage {
        /** Vertex buffer. */
        VERTEX,
        /** Index buffer. */
        INDEX,
        /** Indirect draw or dispatch arguments. */
        INDIRECT,
        /** Uniform buffer. */
        UNIFORM,
        /** Storage buffer for compute access. */
        STORAGE,
        /** Transfer source. */
        TRANSFER_SRC,
        /** Transfer destination. */
        TRANSFER_DST,
        /** Acceleration structure build input. */
        ACCEL_STRUCT_BUILD_INPUT,
        /** Acceleration structure storage. */
        ACCEL_STRUCT_STORAGE,
        /** Ray tracing instance data. */
        ACCEL_STRUCT_INSTANCE,
        /** Shader binding table. */
        SHADER_BINDING_TABLE
    }

    public BufferDesc {
        Objects.requireNonNull(name, "name");
        usage = usage == null || usage.isEmpty() ? EnumSet.noneOf(Usage.class) : EnumSet.copyOf(usage);
        if (size <= 0) {
            throw new IllegalArgumentException(tr("Buffer size must be positive: ") + size);
        }
    }

    public static BufferDesc of(String name, long size, Usage... usages) {
        return new BufferDesc(name, size, toSet(usages), false);
    }

    public static BufferDesc hostVisible(String name, long size, Usage... usages) {
        return new BufferDesc(name, size, toSet(usages), true);
    }

    private static EnumSet<Usage> toSet(Usage... usages) {
        EnumSet<Usage> s = EnumSet.noneOf(Usage.class);
        for (Usage u : usages) {
            s.add(u);
        }
        return s;
    }

    public boolean has(Usage u) {
        return usage.contains(u);
    }

    /** Whether used as a uniform buffer. */
    public boolean isUniform() {
        return has(Usage.UNIFORM);
    }

    /** Whether used as a storage buffer. */
    public boolean isStorage() {
        return has(Usage.STORAGE);
    }

    public BufferDesc withUsage(Usage... extra) {
        EnumSet<Usage> next = EnumSet.copyOf(usage.isEmpty() ? EnumSet.noneOf(Usage.class) : usage);
        for (Usage u : extra) {
            next.add(u);
        }
        return new BufferDesc(name, size, next, hostVisible);
    }
}
