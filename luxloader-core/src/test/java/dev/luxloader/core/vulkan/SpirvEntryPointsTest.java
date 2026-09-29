package dev.luxloader.core.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpirvEntryPointsTest {
    @Test void rejectsWrongEntryOrStageBeforeNativeDriverCall() {
        var b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x07230203).putInt(0x10500).putInt(0).putInt(2).putInt(0);
        b.putInt(5 << 16 | 15).putInt(5).putInt(1).putInt(0x6e69616d).putInt(0);
        assertDoesNotThrow(() -> SpirvEntryPoints.require(b.array(), "main", 5));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SpirvEntryPoints.require(b.array(), "mainDepth", 5)).getMessage().contains("available"));
        assertThrows(IllegalArgumentException.class, () -> SpirvEntryPoints.require(b.array(), "main", 4));
        b.putInt(20, 0);
        assertThrows(IllegalArgumentException.class, () -> SpirvEntryPoints.require(b.array(), "main", 5));
    }
}
