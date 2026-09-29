package dev.luxloader.core.vulkan;

import dev.luxloader.api.gpu.BufferDesc;
import dev.luxloader.api.gpu.GpuCommands;
import dev.luxloader.core.diag.DiagnosticsImpl;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.lwjgl.vulkan.VK10.*;

class VulkanUploadArenaGpuTest {
    @Test void hostOwnedUploadOnlyFramesRetirePagesAtTheCompletionBoundary() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        var diagnostics = new DiagnosticsImpl(Files.createTempDirectory("host-upload"), "host", true);
        var device = VulkanDevice.create("Host upload retirement", true, diagnostics);
        assertNotNull(device);
        try (device; var resources = new VulkanResourceProvider(device, "host", 0); var stack = MemoryStack.stackPush()) {
            int bytes = VulkanUploadArena.PAGE_BYTES + 4;
            var a = resources.buffer(BufferDesc.of("a", bytes, BufferDesc.Usage.TRANSFER_DST, BufferDesc.Usage.TRANSFER_SRC));
            var b = resources.buffer(BufferDesc.of("b", bytes, BufferDesc.Usage.TRANSFER_DST, BufferDesc.Usage.TRANSFER_SRC));
            var commands = (VulkanCommands) device.commands();
            var output = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreateCommandPool(device.vkDevice(), VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default().queueFamilyIndex(device.graphicsQueue().familyIndex()), null, output));
            long pool = output.get(0);
            try {
                byte[] data = new byte[bytes];
                for (int frame = 0; frame < 3; frame++) {
                    List<VkCommandBuffer> pending = new ArrayList<>();
                    commands.withHostEncoder(() -> allocateHostCommand(device, pool), pending::add, () -> {
                        Arrays.fill(data, (byte) 37);
                        commands.begin("host-a").updateBuffers(List.of(new GpuCommands.BufferUpdate(a, 0, data))).end();
                        commands.flush(device.graphicsQueue(), List.of(), List.of());
                        Arrays.fill(data, (byte) 93);
                        commands.begin("host-b").updateBuffers(List.of(new GpuCommands.BufferUpdate(b, 0, data))).end();
                        commands.flush(device.graphicsQueue(), List.of(), List.of());
                        return null;
                    });
                    Arrays.fill(data, (byte) 0);
                    var handles = stack.mallocPointer(pending.size());
                    for (int i = 0; i < pending.size(); i++) handles.put(i, pending.get(i).address());
                    var submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(handles);
                    var queue = new VkQueue(device.graphicsQueue().nativeQueue(), device.vkDevice());
                    assertEquals(VK_SUCCESS, vkQueueSubmit(queue, submit, 0));
                    assertEquals(VK_SUCCESS, vkQueueWaitIdle(queue));
                    var gotA = commands.readBufferBlocking(a.bits(), 0, bytes);
                    var gotB = commands.readBufferBlocking(b.bits(), 0, bytes);
                    try {
                        for (int i = 0; i < bytes; i++) {
                            assertEquals((byte) 37, gotA.get(i));
                            assertEquals((byte) 93, gotB.get(i));
                        }
                    } finally { MemoryUtil.memFree(gotA); MemoryUtil.memFree(gotB); }
                    commands.resetDescriptorPools();
                    assertEquals(VK_SUCCESS, vkResetCommandPool(device.vkDevice(), pool, 0));
                }
                // Failed, never-submitted host work must not leak leases either.
                assertThrows(IllegalStateException.class, () -> commands.withHostEncoder(
                        () -> allocateHostCommand(device, pool), ignored -> fail("Unexpected submit"), () -> {
                            commands.begin("interrupted").updateBuffers(List.of(new GpuCommands.BufferUpdate(a, 0, data)));
                            throw new IllegalStateException("Simulated recording failure");
                        }));
                commands.resetDescriptorPools();
                assertFalse(diagnostics.exportReport().contains("VUID-"));
            } finally { vkDestroyCommandPool(device.vkDevice(), pool, null); }
        }
    }

    @Test void completedOversizedLeaseTrimsCacheAndDoesNotDestroyReusedPagesTwice() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        var device = VulkanDevice.create("Upload cache bound", true, null);
        assertNotNull(device);
        try (device; var resources = new VulkanResourceProvider(device, "trim", 0);
             var arena = new VulkanUploadArena(device); var stack = MemoryStack.stackPush()) {
            int bytes = (VulkanUploadArena.MAX_CACHED_PAGES + 1) * VulkanUploadArena.PAGE_BYTES;
            var target = resources.buffer(BufferDesc.of("target", bytes, BufferDesc.Usage.TRANSFER_DST));
            var commands = (VulkanCommands) device.commands();
            byte[] payload = new byte[bytes]; Arrays.fill(payload, (byte) 23);
            try (var lease = arena.lease()) {
                var cmd = (VulkanCommands.CommandBufferImpl) commands.begin("oversized");
                lease.copy(cmd.handle(), target.bits(), 0, payload, VkBufferCopy.calloc(1, stack));
                cmd.end(); commands.flush(device.graphicsQueue(), List.of(), List.of());
                assertEquals(bytes, arena.reservedBytes());
            }
            assertEquals((long) VulkanUploadArena.MAX_CACHED_PAGES * VulkanUploadArena.PAGE_BYTES, arena.cachedBytes());
            assertEquals(arena.cachedBytes(), arena.reservedBytes());
            long created = arena.createdPages();
            try (var lease = arena.lease()) {
                var cmd = (VulkanCommands.CommandBufferImpl) commands.begin("reuse-after-trim");
                lease.copy(cmd.handle(), target.bits(), 0, new byte[65540], VkBufferCopy.calloc(1, stack));
                cmd.end(); commands.flush(device.graphicsQueue(), List.of(), List.of());
                assertEquals(created, arena.createdPages());
            }
        }
    }

    private static VkCommandBuffer allocateHostCommand(VulkanDevice device, long pool) {
        try (var stack = MemoryStack.stackPush()) {
            var handles = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS, vkAllocateCommandBuffers(device.vkDevice(), VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), handles));
            var command = new VkCommandBuffer(handles.get(0), device.vkDevice());
            assertEquals(VK_SUCCESS, vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)));
            return command;
        }
    }
}
