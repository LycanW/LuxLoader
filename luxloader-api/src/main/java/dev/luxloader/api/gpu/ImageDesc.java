package dev.luxloader.api.gpu;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Texture description exchanged by rendering pipelines and upscale backends.
 * @param name debug name
 * @param width width in pixels
 * @param height height in pixels
 * @param format pixel format
 * @param usage usage flags
 * @param mips mip count, at least 1
 * @param layers array layer count, at least 1
 * @param samples sample count; 1 disables multisampling
 */
public record ImageDesc(
        String name,
        int width,
        int height,
        GpuFormat format,
        Set<Usage> usage,
        int mips,
        int layers,
        int samples) {

    /** Texture usage. */
    public enum Usage {
        /** Color attachment for rendering. */
        COLOR_ATTACHMENT,
        /** Depth/stencil attachment. */
        DEPTH_STENCIL_ATTACHMENT,
        /** Sampled shader input. */
        SAMPLED,
        /** Storage image for compute writes. */
        STORAGE,
        /** Transfer source or destination for upload/readback. */
        TRANSFER_SRC,
        TRANSFER_DST,
        /** External-memory sharing across devices or processes. */
        EXTERNAL
    }

    public ImageDesc {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(format, "format");
        usage = usage == null || usage.isEmpty() ? Set.of() : Set.copyOf(usage);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(tr("Texture dimensions must be positive: ") + width + "x" + height);
        }
        if (mips < 1 || layers < 1 || samples < 1) {
            throw new IllegalArgumentException(tr("mips/layers/samples must be at least 1"));
        }
    }

    public boolean has(Usage u) {
        return usage.contains(u);
    }

    /** Whether dimensions and format match a pass input requirement. */
    public boolean matches(int w, int h) {
        return width == w && height == h;
    }

    public static Builder builder(String name, int width, int height, GpuFormat format) {
        return new Builder(name, width, height, format);
    }

    /** Single-layer texture that supports sampling and color attachment usage. */
    public static ImageDesc renderTarget(String name, int w, int h, GpuFormat format) {
        return builder(name, w, h, format)
                .usage(Usage.COLOR_ATTACHMENT, Usage.SAMPLED, Usage.TRANSFER_SRC)
                .build();
    }

    /** Sampled storage image writable by compute. */
    public static ImageDesc storage(String name, int w, int h, GpuFormat format) {
        return builder(name, w, h, format)
                .usage(Usage.STORAGE, Usage.SAMPLED, Usage.TRANSFER_SRC)
                .build();
    }

    public Builder toBuilder() {
        Builder b = new Builder(name, width, height, format);
        b.usage = EnumSet.copyOf(usage.isEmpty() ? EnumSet.noneOf(Usage.class) : EnumSet.copyOf(usage));
        b.mips = mips;
        b.layers = layers;
        b.samples = samples;
        return b;
    }

    /** Texture descriptor builder. */
    public static final class Builder {
        private final String name;
        private final int width;
        private final int height;
        private final GpuFormat format;
        private Set<Usage> usage = EnumSet.noneOf(Usage.class);
        private int mips = 1;
        private int layers = 1;
        private int samples = 1;

        private Builder(String name, int width, int height, GpuFormat format) {
            this.name = name;
            this.width = width;
            this.height = height;
            this.format = format;
        }

        public Builder usage(Usage... usages) {
            this.usage = usages.length == 0 ? EnumSet.noneOf(Usage.class) : EnumSet.copyOf(Set.of(usages));
            return this;
        }

        public Builder addUsage(Usage u) {
            Set<Usage> next = EnumSet.copyOf(usage.isEmpty() ? EnumSet.noneOf(Usage.class) : EnumSet.copyOf(usage));
            next.add(u);
            this.usage = next;
            return this;
        }

        public Builder mips(int mips) {
            this.mips = mips;
            return this;
        }

        public Builder layers(int layers) {
            this.layers = layers;
            return this;
        }

        public Builder samples(int samples) {
            this.samples = samples;
            return this;
        }

        public ImageDesc build() {
            return new ImageDesc(name, width, height, format, usage, mips, layers, samples);
        }
    }
}
