package dev.luxloader.api.gpu;

import java.util.OptionalInt;

/**
 * Texture formats used by rendering pipelines, named after Vulkan {@code VkFormat}. Use {@link
 * #raw(int, String)} for additional native formats.
 */
public enum GpuFormat {
    UNDEFINED(-1, false, false, 0),
    R8_UNORM(9, false, false, 1),
    R16_SFLOAT(70, false, false, 2),
    R16G16_SFLOAT(81, false, false, 4),
    R16G16B16A16_SFLOAT(97, false, false, 8),
    R32_SFLOAT(100, false, false, 4),
    R32G32_SFLOAT(103, false, false, 8),
    R32G32B32_SFLOAT(106, false, false, 12),
    R32G32B32A32_SFLOAT(109, false, false, 16),
    R32_UINT(98, false, false, 4),
    R32G32_UINT(101, false, false, 8),
    R32G32B32A32_UINT(107, false, false, 16),
    R32_SINT(99, false, false, 4),
    B8G8R8A8_UNORM(44, true, false, 4),
    B8G8R8A8_SRGB(50, true, true, 4),
    R8G8B8A8_UNORM(37, true, false, 4),
    R8G8B8A8_SRGB(43, true, true, 4),
    A2B10G10R10_UNORM_PACK32(58, true, false, 4),
    R16G16B16A16_UNORM(91, false, false, 8),
    R11G11B10_UFLOAT(122, false, false, 4),
    D32_SFLOAT(126, false, false, 4),
    D24_UNORM_S8_UINT(129, false, false, 4),
    D16_UNORM(124, false, false, 2);

    private final int vkFormat;
    private final boolean color;
    private final boolean srgb;
    private final int bytesPerPixel;

    /**
     * Looks up by Vulkan format number. Host and API enum names may differ for the same format (e.g.
     * {@code RGBA8_UNORM} versus {@code R8G8B8A8_UNORM}); name-based lookup can silently make the block
     * atlas unavailable. Vulkan numeric IDs are consistent across implementations.
     * @return matching constant, or {@link #UNDEFINED}
     */
    public static GpuFormat fromVk(int vkFormat) {
        for (GpuFormat format : values()) {
            if (format.vkFormat == vkFormat) {
                return format;
            }
        }
        return UNDEFINED;
    }

    GpuFormat(int vkFormat, boolean color, boolean srgb, int bytesPerPixel) {
        this.vkFormat = vkFormat;
        this.color = color;
        this.srgb = srgb;
        this.bytesPerPixel = bytesPerPixel;
    }

    /** Corresponding VkFormat value; UNDEFINED maps to -1. */
    public int vkFormat() {
        return vkFormat;
    }

    public boolean isColor() {
        return color;
    }

    /** Whether the transfer function is sRGB; hardware decoding requires an SRGB image view. */
    public boolean isSrgb() {
        return srgb;
    }

    public boolean isDepth() {
        return this == D32_SFLOAT || this == D24_UNORM_S8_UINT || this == D16_UNORM;
    }

    public boolean isDepthStencil() {
        return this == D24_UNORM_S8_UINT;
    }

    public int bytesPerPixel() {
        return bytesPerPixel;
    }

    /**
     * Looks up a native {@code VkFormat}, returning {@link #UNDEFINED} when unmapped. This enum covers
     * exchanged pipeline resources, not every vendor-specific, compressed or multiplanar format. Plugins
     * requiring other formats can create resources using {@code GpuDevice#nativeHandle()}.
     */
    public static GpuFormat byVkFormat(int vkFormat) {
        for (GpuFormat f : values()) {
            if (f.vkFormat == vkFormat) {
                return f;
            }
        }
        return UNDEFINED;
    }

    /** Native format as OptionalInt for Vulkan interoperability. */
    public OptionalInt vkFormatOpt() {
        return vkFormat < 0 ? OptionalInt.empty() : OptionalInt.of(vkFormat);
    }

    /** Whether the enum defines a concrete format mapping. */
    public boolean isDefined() {
        return this != UNDEFINED;
    }

    @Override
    public String toString() {
        return name() + (vkFormat >= 0 ? "(" + vkFormat + ")" : "");
    }
}
