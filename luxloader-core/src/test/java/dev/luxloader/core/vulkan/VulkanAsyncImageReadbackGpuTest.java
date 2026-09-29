package dev.luxloader.core.vulkan;

import dev.luxloader.api.gpu.GpuCommands;
import dev.luxloader.api.gpu.GpuFormat;
import dev.luxloader.api.gpu.ImageDesc;
import dev.luxloader.api.gpu.ImageViewDesc;
import dev.luxloader.core.diag.DiagnosticsImpl;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class VulkanAsyncImageReadbackGpuTest {
    @Test
    void asyncImageCopyDeliversOnlyAfterFencePollAndRestoresLayout() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        assumeTrue(VulkanApi.isAvailable(), "Vulkan is unavailable on this host");
        var work = Files.createTempDirectory("lux-async-image-readback");
        var device = VulkanDevice.create("Asynchronous image readback", true,
                new DiagnosticsImpl(work, "async-image-readback", true));
        assumeTrue(device != null, "Vulkan device creation is unavailable");
        try (device; var resources = new VulkanResourceProvider(device, "async-image-readback", 0L)) {
            ImageDesc description = ImageDesc.builder("readback", 4, 3, GpuFormat.R8G8B8A8_UNORM)
                    .usage(ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST,
                            ImageDesc.Usage.SAMPLED)
                    .build();
            var image = resources.image(description);
            var commands = (VulkanCommands) device.commands();
            commands.begin("initialize-readback-image")
                    .clearColor(image, ImageViewDesc.full(), 0.25f, 0.5f, 0.75f, 1.0f)
                    .transition(image, ImageViewDesc.full(), GpuCommands.Access.TRANSFER_WRITE,
                            GpuCommands.Access.SHADER_READ)
                    .end();
            // Setup may wait; the capture path below only submits and polls fences.
            commands.flush(device.graphicsQueue(), java.util.List.of(), java.util.List.of());

            AtomicReference<GpuCommands.ImageReadback> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            assertTrue(commands.readImageAsync(image, description, result::set, failure::set));
            assertEquals("SHADER_READ_ONLY_OPTIMAL", commands.imageLayout(image));
            assertEquals(1, commands.flushAsync(device.graphicsQueue(), java.util.List.of(), java.util.List.of()));
            assertTrue(commands.finishHostFrameAsync(device.graphicsQueue()));
            assertNull(result.get(), "async readback callback is delivered by a completion poll");

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (result.get() == null && failure.get() == null && System.nanoTime() < deadline) {
                commands.pollHostFrameCompletions();
                if (result.get() == null && failure.get() == null) Thread.sleep(1L);
            }
            assertNull(failure.get(), () -> "asynchronous image readback failed: " + failure.get());
            GpuCommands.ImageReadback readback = result.get();
            assertNotNull(readback, "GPU completion fence did not signal before timeout");
            assertEquals(GpuFormat.R8G8B8A8_UNORM, readback.format());
            assertEquals(4, readback.width());
            assertEquals(3, readback.height());
            assertEquals(16, readback.rowStride());
            assertEquals(ByteOrder.nativeOrder().toString(), readback.byteOrder());
            assertEquals("TIGHT_ROW_MAJOR_MIP0_LAYER0", readback.payloadLayout());
            byte[] bytes = readback.data();
            assertEquals(48, bytes.length);
            for (int i = 0; i < bytes.length; i += 4) {
                assertEquals(64, Byte.toUnsignedInt(bytes[i]));
                assertEquals(128, Byte.toUnsignedInt(bytes[i + 1]));
                assertEquals(191, Byte.toUnsignedInt(bytes[i + 2]));
                assertEquals(255, Byte.toUnsignedInt(bytes[i + 3]));
            }
            // The earlier submission fence is already complete because the ordered frame fence signaled.
            commands.drainSubmissions();
            assertEquals("SHADER_READ_ONLY_OPTIMAL", commands.imageLayout(image));
        }
    }
}
