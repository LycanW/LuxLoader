package dev.luxloader.core.vulkan;

import dev.luxloader.api.gpu.BufferDesc;
import dev.luxloader.api.gpu.GpuCommands;
import dev.luxloader.core.diag.DiagnosticsImpl;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/** Explicit recording benchmark; GPU completion waits stay outside the timed region. */
class VulkanUploadBenchmarkGpuTest {
    @Test void recordsTerrainSizedBatches() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline")
                && Boolean.getBoolean("luxloader.test.uploadBenchmark"));
        var diagnostics = new DiagnosticsImpl(Files.createTempDirectory("upload-benchmark"), "upload", false);
        var device = VulkanDevice.create("Upload recording benchmark", false, diagnostics);
        assertNotNull(device);
        try (device; var resources = new VulkanResourceProvider(device, "upload", 0)) {
            var commands = (VulkanCommands) device.commands();
            int bytes = 32 * 1024 * 1024;
            var buffer = resources.buffer(BufferDesc.of("terrain", bytes,
                    BufferDesc.Usage.TRANSFER_DST, BufferDesc.Usage.TRANSFER_SRC));
            byte[] data = new byte[bytes];
            Arrays.fill(data, (byte) 87);
            var updates = List.of(new GpuCommands.BufferUpdate(buffer, 0, data));
            double[] times = new double[32];
            for (int frame = -8; frame < times.length; frame++) {
                var cmd = commands.begin("upload-frame");
                long start = System.nanoTime();
                cmd.updateBuffers(updates);
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                cmd.end();
                commands.flush(device.graphicsQueue(), List.of(), List.of());
                if (frame >= 0) times[frame] = elapsed;
            }
            Arrays.sort(times);
            System.out.printf(Locale.ROOT, "UPLOAD_BENCHMARK 32MiB median=%.3fms p95=%.3fms%n", times[16], times[30]);
            var result = commands.readBufferBlocking(buffer.bits(), 0, bytes);
            try {
                for (int i = 0; i < bytes; i++) assertEquals((byte) 87, result.get(i));
            } finally {
                MemoryUtil.memFree(result);
            }
        }
    }
}
