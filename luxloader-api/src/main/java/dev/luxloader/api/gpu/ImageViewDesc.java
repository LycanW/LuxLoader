package dev.luxloader.api.gpu;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * Image view description. Multiple views can expose different formats or subresource ranges, e.g.
 * non-sRGB input and sRGB output.
 * @param format view format, or null to inherit the image format
 * @param baseMip first mip
 * @param mipCount mip count; nonpositive means all remaining mips
 * @param baseLayer first array layer
 * @param layerCount layer count; nonpositive means all remaining layers
 * @param asStorage whether compute writes through this view
 * @param swizzleRgba optional four-element {@code VkComponentSwizzle} array
 */
public record ImageViewDesc(
        GpuFormat format,
        int baseMip,
        int mipCount,
        int baseLayer,
        int layerCount,
        boolean asStorage,
        int[] swizzleRgba) {

    public ImageViewDesc {
        if (baseMip < 0 || baseLayer < 0) {
            throw new IllegalArgumentException(tr("baseMip/baseLayer must not be negative"));
        }
        swizzleRgba = swizzleRgba == null ? null : swizzleRgba.clone();
    }

    /** Default full-range view using the texture's format. */
    public static ImageViewDesc full() {
        return new ImageViewDesc(null, 0, 0, 0, 0, false, null);
    }

    /** Sampled view with an explicit format. */
    public static ImageViewDesc sampled(GpuFormat format) {
        return new ImageViewDesc(format, 0, 0, 0, 0, false, null);
    }

    /** Storage view for compute writes. */
    public static ImageViewDesc storage(GpuFormat format) {
        return new ImageViewDesc(format, 0, 0, 0, 0, true, null);
    }

    public boolean hasSwizzle() {
        return swizzleRgba != null && swizzleRgba.length == 4;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ImageViewDesc v)) {
            return false;
        }
        return baseMip == v.baseMip && mipCount == v.mipCount && baseLayer == v.baseLayer
                && layerCount == v.layerCount && asStorage == v.asStorage && format == v.format
                && java.util.Arrays.equals(swizzleRgba, v.swizzleRgba);
    }

    @Override
    public int hashCode() {
        return Objects.hash(format, baseMip, mipCount, baseLayer, layerCount, asStorage,
                java.util.Arrays.hashCode(swizzleRgba));
    }

    @Override
    public String toString() {
        return "ImageView[" + (format == null ? "inherit" : format) + " mip=" + baseMip + "+" + mipCount
                + " layer=" + baseLayer + "+" + layerCount + (asStorage ? " storage" : "") + "]";
    }
}
