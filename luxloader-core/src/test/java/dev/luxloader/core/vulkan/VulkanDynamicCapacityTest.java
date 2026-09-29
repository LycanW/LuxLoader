package dev.luxloader.core.vulkan;

import dev.luxloader.api.gpu.*;
import dev.luxloader.core.diag.DiagnosticsImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VulkanDynamicCapacityTest {
    @TempDir Path work;

    @Test void variablePrimitiveCountsReuseDeclaredScratchAndProduceTimings() {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        var diagnostics = new DiagnosticsImpl(work, "dynamic-capacity", true);
        try (var device = VulkanDevice.create("dynamic capacity regression", true, diagnostics)) {
            assertNotNull(device);
            assumeTrue(device.capabilities().supports("VK_KHR_acceleration_structure"));
            try (var resources = new VulkanResourceProvider(device, "capacity", 0)) {
                int capacity = 8192;
                var vertices = resources.buffer(BufferDesc.hostVisible("vertices", 36,
                        BufferDesc.Usage.ACCEL_STRUCT_BUILD_INPUT));
                var indices = resources.buffer(BufferDesc.hostVisible("indices", capacity * 12L,
                        BufferDesc.Usage.ACCEL_STRUCT_BUILD_INPUT));
                var v = ByteBuffer.allocateDirect(36).order(ByteOrder.nativeOrder());
                v.asFloatBuffer().put(new float[] {0,0,0, 1,0,0, 0,1,0});
                resources.upload(vertices, 0, v);
                var i = ByteBuffer.allocateDirect(capacity * 12).order(ByteOrder.nativeOrder());
                for (int n = 0; n < capacity; n++) i.putInt(0).putInt(1).putInt(2);
                i.flip(); resources.upload(indices, 0, i);
                var blas = resources.accelerator(AccelStructDesc.dynamicBlas("dynamic", capacity, 3,
                        new AccelStructDesc.BlasLayout(12, AccelGeometry.IndexType.UINT32, false)));
                var tlas = resources.accelerator(AccelStructDesc.tlas("instances", 32));
                var commands = (VulkanCommands) device.commands();
                int[] sizes = {1, 2, 255, 256, 257, 1023, 1024, 2047, 4095, 8192, 33, 1};
                for (int count : sizes) {
                    commands.beginTimestamp("variable-blas");
                    var cmd = commands.begin("build-" + count);
                    resources.recordBuildAccelerator(cmd, blas, new AccelGeometry(vertices, 0, 3, 12,
                            indices, 0, count * 3, AccelGeometry.IndexType.UINT32, false));
                    resources.recordBuildTopLevel(cmd, tlas, List.of(new AccelInstance(blas,
                            AccelInstance.identityTransform(), 0, 255, 0, false)));
                    cmd.end(); commands.endTimestamp("variable-blas");
                    commands.flush(device.graphicsQueue(), List.of(), List.of());
                    commands.collectPassTimings();
                    assertEquals(0, commands.pendingRecordingCount());
                }
                // Multiple row batches use one semantic label in the same frame.
                // They need separate query slots, and their total must fit inside
                // the enclosing interval instead of ending at an unrelated batch.
                commands.beginTimestamp("all-batches");
                for (int batch = 0; batch < 4; batch++) {
                    commands.beginTimestamp("repeated-batch");
                    var cmd = commands.begin("repeated-build");
                    resources.recordBuildAccelerator(cmd, blas, new AccelGeometry(vertices, 0, 3, 12,
                            indices, 0, 300, AccelGeometry.IndexType.UINT32, false));
                    cmd.end(); commands.endTimestamp("repeated-batch");
                }
                commands.endTimestamp("all-batches");
                commands.flush(device.graphicsQueue(), List.of(), List.of());
                commands.collectPassTimings();
                String report = diagnostics.exportReport();
                double batchMs = metric(report, "gpu.pass.repeated-batch");
                double totalMs = metric(report, "gpu.pass.all-batches");
                assertTrue(batchMs > 0 && batchMs <= totalMs + .001, report);
                assertTrue(diagnostics.exportReport().contains("gpu.pass.avg.variable-blas"));
                assertTrue(commands.recyclingFailures().isEmpty(), commands.recyclingFailures().toString());
            }
        }
    }

    private static double metric(String report, String name) {
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name) + " = ([0-9.]+)").matcher(report);
        assertTrue(match.find(), "missing " + name);
        return Double.parseDouble(match.group(1));
    }
}
