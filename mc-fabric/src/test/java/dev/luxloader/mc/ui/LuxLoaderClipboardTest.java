package dev.luxloader.mc.ui;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.junit.jupiter.api.Assertions.*;

class LuxLoaderClipboardTest {
    @Test void multiMegabyteChineseReportDoesNotConsumeTheNativeStack() {
        String report = "完整诊断报告 / reflection / 😀\n".repeat(100000);
        try (var stack = MemoryStack.stackPush()) {
            int before = stack.getPointer();
            LuxLoaderClipboard.copy(report, bytes -> {
                assertTrue(bytes.isDirect());
                assertTrue(bytes.remaining() > 1024 * 1024);
                assertEquals(0, bytes.get(bytes.limit()-1));
                assertEquals(report, MemoryUtil.memUTF8(bytes, bytes.remaining()-1));
                assertEquals(before, stack.getPointer());
            });
            assertEquals(before, stack.getPointer());
        }
    }
}
