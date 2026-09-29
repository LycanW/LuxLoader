package dev.luxloader.core.diag;

import static dev.luxloader.api.i18n.Messages.tr;

/**
 * Pure P6 PPM encoder for captured frame evidence. Avoids java.desktop/ImageIO dependencies in core
 * and performs no GPU or disk operations. Pixel samples can prove activity but not visible effects;
 * inspect captured images to validate appearance.
 */
public final class FrameDump {

    /** {@code VK_FORMAT_R8G8B8A8_UNORM} */
    public static final int FORMAT_R8G8B8A8_UNORM = 37;

    /** {@code VK_FORMAT_B8G8R8A8_UNORM} */
    public static final int FORMAT_B8G8R8A8_UNORM = 44;

    private FrameDump() {
    }

    /** Bytes per pixel for a supported VkFormat; reject unsupported formats rather than guessing. */
    public static int bytesPerPixel(int vkFormat) {
        return switch (vkFormat) {
            case FORMAT_R8G8B8A8_UNORM, FORMAT_B8G8R8A8_UNORM -> 4;
            default -> throw new IllegalArgumentException(
                    tr("Frame capture does not support VkFormat: ") + vkFormat + tr(" (add an explicit mapping when required; do not guess)"));
        };
    }

    /**
     * Encodes P6 PPM.
     * @param pixels tightly packed pixels without row padding
     * @param vkFormat channel order; RGBA uses RGB directly, BGRA swaps red/blue
     * @return complete PPM file bytes
     */
    public static byte[] toPpm(byte[] pixels, int width, int height, int vkFormat) {
        int bpp = bytesPerPixel(vkFormat);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(tr("Frame capture dimensions must be positive: ") + width + "x" + height);
        }
        long expected = (long) width * height * bpp;
        if (pixels.length != expected) {
            throw new IllegalArgumentException(tr("Unexpected pixel byte count: expected ") + expected
                    + "（" + width + "x" + height + "x" + bpp + tr("), got ") + pixels.length);
        }
        boolean bgra = vkFormat == FORMAT_B8G8R8A8_UNORM;

        byte[] header = ("P6\n" + width + " " + height + "\n255\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = new byte[header.length + width * height * 3];
        System.arraycopy(header, 0, out, 0, header.length);

        int src = 0;
        int dst = header.length;
        for (int i = 0; i < width * height; i++) {
            int c0 = pixels[src] & 0xFF;
            int c1 = pixels[src + 1] & 0xFF;
            int c2 = pixels[src + 2] & 0xFF;
            src += bpp;
            // PPM always uses RGB channel order.
            out[dst++] = (byte) (bgra ? c2 : c0);
            out[dst++] = (byte) c1;
            out[dst++] = (byte) (bgra ? c0 : c2);
        }
        return out;
    }
}
