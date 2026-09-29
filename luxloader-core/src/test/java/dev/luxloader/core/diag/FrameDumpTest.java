package dev.luxloader.core.diag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link FrameDump} encoding without a GPU. Vulkan BGRA memory must be reordered to PPM RGB;
 * incorrect ordering otherwise silently swaps red and blue in diagnostic images.
 */
class FrameDumpTest {

    private static byte[] concat(byte[] header, byte[] body) {
        byte[] out = new byte[header.length + body.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }

    @Test
    void writesP6HeaderWithSizeAndMaxValue() {
        byte[] ppm = FrameDump.toPpm(new byte[2 * 3 * 4], 2, 3, FrameDump.FORMAT_R8G8B8A8_UNORM);
        String header = new String(ppm, 0, 11, StandardCharsets.US_ASCII);
        assertEquals("P6\n2 3\n255\n", header);
        assertEquals(11 + 2 * 3 * 3, ppm.length, "11 header bytes plus 3 bytes per pixel");
    }

    @Test
    void rgbaIsCopiedInOrder() {
        // One pixel: R=0x11, G=0x22, B=0x33, A=0x44.
        byte[] px = {(byte) 0x11, (byte) 0x22, (byte) 0x33, (byte) 0x44};
        byte[] ppm = FrameDump.toPpm(px, 1, 1, FrameDump.FORMAT_R8G8B8A8_UNORM);
        assertArrayEquals(new byte[] {(byte) 0x11, (byte) 0x22, (byte) 0x33},
                new byte[] {ppm[11], ppm[12], ppm[13]});
    }

    @Test
    void bgraIsSwappedToRgb() {
        // BGRA memory contains 33 22 11 44; PPM output must contain 11 22 33.
        byte[] px = {(byte) 0x33, (byte) 0x22, (byte) 0x11, (byte) 0x44};
        byte[] ppm = FrameDump.toPpm(px, 1, 1, FrameDump.FORMAT_B8G8R8A8_UNORM);
        assertArrayEquals(new byte[] {(byte) 0x11, (byte) 0x22, (byte) 0x33},
                new byte[] {ppm[11], ppm[12], ppm[13]},
                "BGRA output must swap red and blue into RGB order");
    }

    @Test
    void rowPitchIsNotAssumed() {
        // Packed 2x2 image: the second row must not read padding from the first row.
        byte[] px = {
            (byte) 1, (byte) 0, (byte) 0, (byte) 0xFF,   // Pixel (0,0) is red.
            (byte) 2, (byte) 0, (byte) 0, (byte) 0xFF,   // (1,0)
            (byte) 3, (byte) 0, (byte) 0, (byte) 0xFF,   // (0,1)
            (byte) 4, (byte) 0, (byte) 0, (byte) 0xFF};  // (1,1)
        byte[] ppm = FrameDump.toPpm(px, 2, 2, FrameDump.FORMAT_R8G8B8A8_UNORM);
        assertEquals(1, ppm[11], "(0,0).R");
        assertEquals(2, ppm[14], "(1,0).R");
        assertEquals(3, ppm[17], "Pixel (0,1) red channel with no row padding");
        assertEquals(4, ppm[20], "(1,1).R");
    }

    @Test
    void rejectsWrongSizesAndFormats() {
        assertThrows(IllegalArgumentException.class,
                () -> FrameDump.toPpm(new byte[3], 1, 1, FrameDump.FORMAT_R8G8B8A8_UNORM),
                "Reject mismatched byte counts instead of writing a partial image");
        assertThrows(IllegalArgumentException.class,
                () -> FrameDump.toPpm(new byte[4], 0, 1, FrameDump.FORMAT_R8G8B8A8_UNORM));
        assertThrows(IllegalArgumentException.class,
                () -> FrameDump.bytesPerPixel(97),
                "Reject unmapped R16G16B16A16_SFLOAT rather than assuming four-byte pixels");
    }
}
