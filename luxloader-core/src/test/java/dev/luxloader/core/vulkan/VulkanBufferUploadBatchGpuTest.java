package dev.luxloader.core.vulkan;
import dev.luxloader.api.gpu.*;
import dev.luxloader.core.diag.DiagnosticsImpl;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
class VulkanBufferUploadBatchGpuTest {
    @Test void pagesRemainPrivateAcrossPendingBatchesAndCanBeReusedAfterCompletion() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        var diagnostics = new DiagnosticsImpl(Files.createTempDirectory("upload-pending"), "pending", true);
        var device = VulkanDevice.create("Pending upload pages", true, diagnostics);
        assertNotNull(device);
        try (device; var resources = new VulkanResourceProvider(device, "pending", 0)) {
            int bytes = VulkanUploadArena.PAGE_BYTES + 4;
            var a = resources.buffer(BufferDesc.of("a", bytes, BufferDesc.Usage.TRANSFER_DST, BufferDesc.Usage.TRANSFER_SRC));
            var b = resources.buffer(BufferDesc.of("b", bytes, BufferDesc.Usage.TRANSFER_DST, BufferDesc.Usage.TRANSFER_SRC));
            var commands = (VulkanCommands) device.commands();
            byte[] data = new byte[bytes];
            for (int round = 0; round < 3; round++) {
                Arrays.fill(data, (byte) (31 + round));
                commands.begin("a").updateBuffers(List.of(new GpuCommands.BufferUpdate(a, 0, data))).end();
                commands.flushAsync(device.graphicsQueue(), List.of(), List.of());
                Arrays.fill(data, (byte) (71 + round));
                commands.begin("b").updateBuffers(List.of(new GpuCommands.BufferUpdate(b, 0, data))).end();
                commands.flushAsync(device.graphicsQueue(), List.of(), List.of());
                Arrays.fill(data, (byte) 0);
                commands.drainSubmissions();
                var gotA = commands.readBufferBlocking(a.bits(), 0, bytes);
                var gotB = commands.readBufferBlocking(b.bits(), 0, bytes);
                try {
                    for (int i = 0; i < bytes; i++) {
                        assertEquals((byte) (31 + round), gotA.get(i));
                        assertEquals((byte) (71 + round), gotB.get(i));
                    }
                } finally { MemoryUtil.memFree(gotA); MemoryUtil.memFree(gotB); }
            }
            // A discarded recording also returns its lease without executing stale copies.
            commands.begin("discarded").updateBuffers(List.of(new GpuCommands.BufferUpdate(a, 0, data)));
            commands.discardPendingRecordings();
            commands.resetDescriptorPools();
            assertFalse(diagnostics.exportReport().contains("VUID-"));
        }
    }

    @Test void rejectsInvalidBatchBeforeRecordingAnyCopy() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        var device = VulkanDevice.create("Upload validation", true, null);
        assertNotNull(device);
        try (device; var resources = new VulkanResourceProvider(device, "validation", 0)) {
            var target = resources.buffer(BufferDesc.of("target", 16, BufferDesc.Usage.TRANSFER_DST, BufferDesc.Usage.TRANSFER_SRC));
            var commands = (VulkanCommands) device.commands();
            var cmd = commands.begin("validate-first");
            cmd.updateBuffer(target, 0, new byte[16]);
            byte[] unexpected = new byte[16]; Arrays.fill(unexpected, (byte) 99);
            assertThrows(IllegalArgumentException.class, () -> cmd.updateBuffers(List.of(
                    new GpuCommands.BufferUpdate(target, 0, unexpected), new GpuCommands.BufferUpdate(target, 12, unexpected))));
            cmd.end(); commands.flush(device.graphicsQueue(), List.of(), List.of());
            var result = commands.readBufferBlocking(target.bits(), 0, 16);
            try { for (int i = 0; i < 16; i++) assertEquals(0, result.get(i)); }
            finally { MemoryUtil.memFree(result); }
        }
    }

    @Test void largeBatchesKeepOrderedOverlapsAndCopyBytesDuringRecording() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        var device=VulkanDevice.create("Batch upload test",true,new DiagnosticsImpl(Files.createTempDirectory("batch-upload"),"batch",true));
        assertNotNull(device);
        try(device;var resources=new VulkanResourceProvider(device,"batch",0)) {
            int size=196612;
            var a=resources.buffer(BufferDesc.of("a",size,BufferDesc.Usage.STORAGE,BufferDesc.Usage.TRANSFER_DST,BufferDesc.Usage.TRANSFER_SRC));
            var b=resources.buffer(BufferDesc.of("b",size,BufferDesc.Usage.STORAGE,BufferDesc.Usage.TRANSFER_DST,BufferDesc.Usage.TRANSFER_SRC));
            var commands=(VulkanCommands)device.commands();
            byte[] initial=new byte[size]; Arrays.fill(initial,(byte)17);
            byte[] patch=new byte[65540]; Arrays.fill(patch,(byte)83);
            byte[] tail=new byte[16]; Arrays.fill(tail,(byte)125);
            var cmd=commands.begin("batch");
            cmd.updateBuffers(List.of(new GpuCommands.BufferUpdate(a,0,initial),new GpuCommands.BufferUpdate(b,0,initial),
                    new GpuCommands.BufferUpdate(a,65532,patch),new GpuCommands.BufferUpdate(a,131064,tail)));
            // No upload data may be retained by the command buffer beyond recording.
            Arrays.fill(initial,(byte)0); Arrays.fill(patch,(byte)0); Arrays.fill(tail,(byte)0);
            cmd.end(); commands.flush(device.graphicsQueue(),List.of(),List.of()); commands.waitIdleAll();
            ByteBuffer gotA=commands.readBufferBlocking(a.bits(),0,size),gotB=commands.readBufferBlocking(b.bits(),0,size);
            try {
                for(int i=0;i<size;i++) {
                    int expected=i>=131064&&i<131080?125:i>=65532&&i<131072?83:17;
                    assertEquals(expected,gotA.get(i)&255,"offset "+i);
                    assertEquals(17,gotB.get(i)&255,"other destination");
                }
            } finally {MemoryUtil.memFree(gotA);MemoryUtil.memFree(gotB);}
        }
    }
    @Test void gpuCopyPreservesUploadedBytesAndOrdersFollowingPatch() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        var diagnostics = new DiagnosticsImpl(Files.createTempDirectory("buffer-copy"), "copy", true);
        var device = VulkanDevice.create("Buffer growth copy", true, diagnostics);
        assertNotNull(device);
        try (device; var resources = new VulkanResourceProvider(device, "copy", 0)) {
            int size = 131072;
            var a = resources.buffer(BufferDesc.of("a", size, BufferDesc.Usage.TRANSFER_DST, BufferDesc.Usage.TRANSFER_SRC));
            var b = resources.buffer(BufferDesc.of("b", size * 2L, BufferDesc.Usage.TRANSFER_DST, BufferDesc.Usage.TRANSFER_SRC));
            var commands = (VulkanCommands) device.commands();
            byte[] original = new byte[size]; Arrays.fill(original, (byte) 53);
            byte[] patch = new byte[64]; Arrays.fill(patch, (byte) 91);
            var cmd = commands.begin("grow");
            assertThrows(IllegalArgumentException.class, () -> cmd.copyBuffer(a, 0, a, 0, 4));
            assertThrows(IllegalArgumentException.class, () -> cmd.copyBuffer(a, 1, b, 0, 4));
            assertThrows(IllegalArgumentException.class, () -> cmd.copyBuffer(a, Long.MAX_VALUE - 3, b, 0, 4));
            cmd.updateBuffers(List.of(new GpuCommands.BufferUpdate(a, 0, original)));
            cmd.copyBuffer(a, 0, b, 128, size).updateBuffer(b, 256, patch).end();
            commands.flush(device.graphicsQueue(), List.of(), List.of()); commands.waitIdleAll();
            ByteBuffer result = commands.readBufferBlocking(b.bits(), 128, size);
            try {
                for (int i = 0; i < size; i++) assertEquals(i >= 128 && i < 192 ? 91 : 53, result.get(i) & 255);
            } finally { MemoryUtil.memFree(result); }
            assertFalse(diagnostics.exportReport().contains("VUID-"));
        }
    }

}
