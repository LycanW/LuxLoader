package dev.luxloader.core.vulkan;

import dev.luxloader.core.util.LuxException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import static dev.luxloader.api.i18n.Messages.tr;
import static org.lwjgl.vulkan.VK10.*;

/** Persistently mapped upload pages. Only completed or discarded recordings may return a lease. */
final class VulkanUploadArena implements AutoCloseable {
    static final int PAGE_BYTES = 4 * 1024 * 1024;
    static final int MAX_CACHED_PAGES = 16;
    private final VulkanDevice device;
    private final ArrayDeque<Page> spare = new ArrayDeque<>();
    private final Set<Page> pages = new HashSet<>();
    private long created, reused, copiedBytes, copyCommands, peakBytes;
    private boolean closed;

    // Identity semantics: ByteBuffer's content-based hash changes as uploads are written.
    private static final class Page {
        final long buffer, memory;
        final ByteBuffer mapped;
        Page(long buffer, long memory, ByteBuffer mapped) {
            this.buffer = buffer;
            this.memory = memory;
            this.mapped = mapped;
        }
    }

    VulkanUploadArena(VulkanDevice device) { this.device = device; }

    /** A lease remains private to one recording until its completion is proven. */
    final class Lease implements AutoCloseable {
        private final List<Page> owned = new ArrayList<>();
        private Page current;
        private int cursor;
        private boolean returned;

        void copy(VkCommandBuffer commands, long destination, long offset, byte[] source, VkBufferCopy.Buffer region) {
            if (returned || closed) throw new IllegalStateException("Upload lease is closed");
            for (int read = 0; read < source.length;) {
                if (current == null || cursor == PAGE_BYTES) {
                    current = acquirePage();
                    owned.add(current);
                    cursor = 0;
                }
                int bytes = Math.min(PAGE_BYTES - cursor, source.length - read);
                current.mapped.put(cursor, source, read, bytes);
                region.get(0).srcOffset(cursor).dstOffset(offset + read).size(bytes);
                vkCmdCopyBuffer(commands, current.buffer, destination, region);
                cursor += bytes;
                read += bytes;
                copiedBytes += bytes;
                copyCommands++;
            }
        }

        @Override public void close() {
            if (returned) return;
            returned = true;
            if (!closed) for (Page page : owned) {
                if (spare.size() < MAX_CACHED_PAGES) spare.addLast(page);
                else destroy(page);
            }
            owned.clear();
            current = null;
        }
    }

    Lease lease() {
        if (closed) throw new IllegalStateException("Upload arena is closed");
        return new Lease();
    }

    private Page acquirePage() {
        Page available = spare.pollFirst();
        if (available != null) { reused++; return available; }
        long buffer = 0, memory = 0;
        boolean mapped = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var output = stack.mallocLong(1);
            var create = VkBufferCreateInfo.calloc(stack).sType$Default().size(PAGE_BYTES)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateBuffer(device.vkDevice(), create, null, output), "Failed to create staging buffer: ");
            buffer = output.get(0);
            var requirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device.vkDevice(), buffer, requirements);
            int memoryType = -1;
            int properties = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            for (int i = 0; i < device.memoryProperties().memoryTypeCount(); i++) {
                if ((requirements.memoryTypeBits() & (1 << i)) != 0
                        && (device.memoryProperties().memoryTypes(i).propertyFlags() & properties) == properties) {
                    memoryType = i;
                    break;
                }
            }
            if (memoryType < 0) throw new LuxException(tr("No usable GPU memory type"));
            var allocate = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(memoryType);
            check(vkAllocateMemory(device.vkDevice(), allocate, null, output), "Failed to allocate staging memory: ");
            memory = output.get(0);
            check(vkBindBufferMemory(device.vkDevice(), buffer, memory, 0), "vkBindBufferMemory: ");
            var address = stack.mallocPointer(1);
            check(vkMapMemory(device.vkDevice(), memory, 0, PAGE_BYTES, 0, address), "Failed to map staging buffer: ");
            mapped = true;
            Page page = new Page(buffer, memory, MemoryUtil.memByteBuffer(address.get(0), PAGE_BYTES));
            pages.add(page);
            created++;
            peakBytes = Math.max(peakBytes, (long) pages.size() * PAGE_BYTES);
            return page;
        } catch (RuntimeException | Error failure) {
            if (mapped) vkUnmapMemory(device.vkDevice(), memory);
            if (buffer != 0) vkDestroyBuffer(device.vkDevice(), buffer, null);
            if (memory != 0) vkFreeMemory(device.vkDevice(), memory, null);
            throw failure;
        }
    }

    long reservedBytes() { return (long) pages.size() * PAGE_BYTES; }
    long cachedBytes() { return (long) spare.size() * PAGE_BYTES; }
    long createdPages() { return created; }

    void report() {
        var diagnostics = device.diagnostics();
        if (diagnostics == null) return;
        diagnostics.metric("upload.staging.reserved", reservedBytes(), "bytes");
        diagnostics.metric("upload.staging.cached", cachedBytes(), "bytes");
        diagnostics.metric("upload.staging.reserved.peak", peakBytes, "bytes");
        diagnostics.metric("upload.staging.pages-created", created, "pages");
        diagnostics.metric("upload.staging.pages-reused", reused, "pages");
        diagnostics.metric("upload.staging.copied", copiedBytes, "bytes");
        diagnostics.metric("upload.staging.copy-commands", copyCommands, "commands");
    }

    private void destroy(Page page) {
        vkUnmapMemory(device.vkDevice(), page.memory);
        vkDestroyBuffer(device.vkDevice(), page.buffer, null);
        vkFreeMemory(device.vkDevice(), page.memory, null);
        pages.remove(page);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Page page : List.copyOf(pages)) destroy(page);
        spare.clear();
    }

    private static void check(int result, String prefix) {
        if (result != VK_SUCCESS) throw new LuxException(tr(prefix) + VulkanDevice.resultName(result));
    }
}
