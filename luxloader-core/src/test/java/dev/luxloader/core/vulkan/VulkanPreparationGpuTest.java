package dev.luxloader.core.vulkan;

import dev.luxloader.api.gpu.*;
import dev.luxloader.core.diag.DiagnosticsImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryUtil;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VulkanPreparationGpuTest {
    @TempDir Path output;
    @Test void repeatedUnfinishedRecordingsRejectAndACompleteUploadIsActuallyGpuVisible() {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        var diagnostics = new DiagnosticsImpl(output, "preparation", true);
        try (var device = VulkanDevice.create("Preparation publication", true, diagnostics);
             var resources = new VulkanResourceProvider(device, "preparation", 0)) {
            var commands = (VulkanCommands)device.commands();
            var buffer = resources.buffer(BufferDesc.of("material", 16, BufferDesc.Usage.TRANSFER_DST, BufferDesc.Usage.TRANSFER_SRC));
            for (int attempt = 0; attempt < 2; attempt++) {
                commands.begin("unfinished-" + attempt).updateBuffer(buffer, 0, new byte[16]);
                assertThrows(IllegalStateException.class, () -> commands.completePreparation(device.graphicsQueue()));
                assertEquals(0, commands.pendingRecordingCount());
                assertEquals(0, commands.drainSubmissions());
            }
            byte[] expected = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16};
            commands.begin("complete").updateBuffer(buffer, 0, expected).end();
            long queueWaits = commands.wholeQueueWaitCount();
            commands.completePreparation(device.graphicsQueue());
            assertEquals(queueWaits, commands.wholeQueueWaitCount());
            var actual = commands.readBufferBlocking(buffer.bits(), 0, expected.length);
            try { for (int i = 0; i < expected.length; i++) assertEquals(expected[i], actual.get(i)); }
            finally { MemoryUtil.memFree(actual); }
            commands.withHostEncoder(() -> { throw new AssertionError("No recording requested"); }, ignored -> {}, () -> {
                assertFalse(commands.supportsPreparationSubmission());
                assertThrows(IllegalStateException.class, () -> commands.completePreparation(device.graphicsQueue()));
                return null;
            });
            assertTrue(commands.supportsPreparationSubmission());
            assertFalse(diagnostics.exportReport().contains("VUID-"), diagnostics.exportReport());
        }
    }
}
