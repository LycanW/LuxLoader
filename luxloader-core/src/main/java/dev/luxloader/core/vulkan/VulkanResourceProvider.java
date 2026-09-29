package dev.luxloader.core.vulkan;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.gpu.AccelStructDesc;
import dev.luxloader.api.gpu.AcceleratorHandle;
import dev.luxloader.api.gpu.BufferDesc;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.gpu.ImageDesc;
import dev.luxloader.api.gpu.ImageHandle;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

/**
 * Scoped Vulkan resource provider. Each instance owns and releases its resources on closeAll, enabling
 * safe pipeline switching. Description caches reuse repeated image/buffer requests. Acceleration
 * structures require enabled build/address capabilities and retain their own backing storage;
 * unsupported paths return an explicit unsupported handle.
 */
public final class VulkanResourceProvider implements dev.luxloader.api.pipeline.GpuResourceProvider, AutoCloseable {

    private final VulkanDevice device;
    private final String scopeName;
    private final Map<ImageDesc, ImageHandle> imageCache = new LinkedHashMap<>();
    private final Map<BufferDesc, GpuDevice.Handle> bufferCache = new LinkedHashMap<>();
    private final List<Long> ownedImages = new ArrayList<>();
    private final List<long[]> ownedBuffers = new ArrayList<>(); // [buffer, memory]
    private final List<Long> ownedMemories = new ArrayList<>();
    private final List<Long> ownedViews = new ArrayList<>();
    private final Map<ViewKey, Long> imageViews = new LinkedHashMap<>();
    private long usedBytes;

    /**
     * Per-handle allocation sizes shared by image/buffer accounting so individual releases decrement live
     * usage. Counting cumulative allocation would falsely trigger budget reductions in long-running
     * pipelines.
     */
    private final java.util.Map<Long, Long> bytesByHandle = new java.util.HashMap<>();
    private long budgetBytes;
    private int nextDebugId = 1;

    /** Caches one VkImageView per image/view description pair. */
    private record ViewKey(long image, dev.luxloader.api.gpu.ImageViewDesc view) {
    }

    public VulkanResourceProvider(VulkanDevice device, String scopeName, long budgetBytes) {
        this.device = Objects.requireNonNull(device, "device");
        this.scopeName = scopeName == null ? "default" : scopeName;
        this.budgetBytes = budgetBytes;
        // Give commands a view resolver while retaining ownership in the resource provider.
        if (device.commands() instanceof VulkanCommands vkCommands) {
            vkCommands.setViewResolver(imageBits -> resolveView(
                    ImageHandle.vkImage(imageBits, "resolve"), dev.luxloader.api.gpu.ImageViewDesc.full()));
        }
    }

    @Override
    public ImageHandle image(ImageDesc desc) {
        Objects.requireNonNull(desc, "desc");
        ImageHandle cached = imageCache.get(desc);
        if (cached != null) {
            return cached;
        }
        ImageHandle created = createImageInternal(desc, false);
        imageCache.put(desc, created);
        return created;
    }

    @Override
    public ImageHandle sharedImage(ImageDesc desc) {
        Objects.requireNonNull(desc, "desc");
        ImageDesc shared = desc.has(ImageDesc.Usage.EXTERNAL)
                ? desc
                : desc.toBuilder().addUsage(ImageDesc.Usage.EXTERNAL).build();
        ImageHandle cached = imageCache.get(shared);
        if (cached != null) {
            return cached;
        }
        ImageHandle created = createImageInternal(shared, true);
        imageCache.put(shared, created);
        return created;
    }

    @Override
    public ImageHandle importImage(ImageHandle external, ImageDesc desc) {
        // External import requires platform extensions and handles; report unavailable instead of fabricating a handle.
        throw new UnsupportedOperationException(
                tr("External memory import is not enabled (requires VK_KHR_external_memory_win32). ")
                        + tr("For cross-device texture sharing, implement GpuDevice#importImage in the host adapter."));
    }

    @Override
    public ImageHandle exportHandle(ImageHandle image) {
        if (image == null || image.isNull()) {
            return ImageHandle.win32(0L, "null");
        }
        // Export also requires external-memory extensions; return an empty handle when unavailable.
        return ImageHandle.win32(0L, image.label());
    }

    @Override
    public GpuDevice.Handle buffer(BufferDesc desc) {
        Objects.requireNonNull(desc, "desc");
        GpuDevice.Handle cached = bufferCache.get(desc);
        if (cached != null) {
            return cached;
        }
        GpuDevice.Handle created = createBufferInternal(desc);
        bufferCache.put(desc, created);
        return created;
    }

    @Override
    public void upload(GpuDevice.Handle destination, long offset, ByteBuffer data) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(data, "data");
        device.uploadBuffer(destination.bits(), offset, data);
    }

    @Override
    public void readbackAsync(GpuDevice.Handle source, long offset, long size, Consumer<ByteBuffer> callback) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(callback, "callback");
        // Schedule reading at next-frame start without blocking here.
        device.queueReadback(source.bits(), offset, size, callback);
    }

    /** Acceleration structures indexed by native handle, retaining backing buffers/memory for builds and cleanup. */
    private final java.util.Map<Long, VulkanAccelStructures.Storage> accelByHandle =
            new java.util.LinkedHashMap<>();

    /**
     * Separate acceleration-structure memory accounting because its handle namespace can collide with
     * other resources.
     */
    private final java.util.Map<Long, Long> accelBytesByHandle = new java.util.HashMap<>();

    private VulkanAccelStructures accelStructures;

    private VulkanAccelStructures accel() {
        if (accelStructures == null) {
            accelStructures = new VulkanAccelStructures(device);
        }
        return accelStructures;
    }

    /**
     * Checks actual ray tracing availability, including buffer device address support required by
     * acceleration structures and their geometry/instance inputs.
     */
    private boolean rayTracingUsable() {
        return device.capabilities().supportsAll(
                        "VK_KHR_acceleration_structure",
                        "VK_KHR_deferred_host_operations",
                        "VK_KHR_buffer_device_address")
                && device.capabilities().supportsAny(
                        "VK_KHR_ray_query", "VK_KHR_ray_tracing_pipeline");
    }

    @Override
    public AcceleratorHandle accelerator(AccelStructDesc desc) {
        Objects.requireNonNull(desc, "desc");
        if (!rayTracingUsable()) {
            return AcceleratorHandle.unsupported(desc.name());
        }
        try {
            VulkanAccelStructures.Storage storage = accel().create(desc);
            accelByHandle.put(storage.handle().nativeHandle(), storage);
            accelBytesByHandle.put(storage.handle().nativeHandle(), storage.size());
            usedBytes += storage.size();
            device.diagnostics().info(tr("Created acceleration structure: ") + desc.name()
                    + "（" + desc.type() + "，" + storage.size() + tr(" bytes, device address 0x")
                    + Long.toHexString(storage.address()) + "）");
            return storage.handle();
        } catch (RuntimeException e) {
            // Report unsupported allocation so the caller can choose a fallback.
            device.diagnostics().warn(tr("Failed to create acceleration structure: ") + desc.name() + " -> " + e);
            return AcceleratorHandle.unsupported(desc.name());
        }
    }

    @Override
    public void buildAccelerator(AcceleratorHandle blas, dev.luxloader.api.gpu.AccelGeometry geometry) {
        Objects.requireNonNull(blas, "blas");
        Objects.requireNonNull(geometry, "geometry");
        accel().buildBlas(requireAccel(blas), geometry);
    }

    @Override
    public void buildTopLevel(AcceleratorHandle tlas,
                              java.util.List<dev.luxloader.api.gpu.AccelInstance> instances) {
        Objects.requireNonNull(tlas, "tlas");
        Objects.requireNonNull(instances, "instances");
        accel().buildTopLevel(requireAccel(tlas), instances);
    }

    private VulkanAccelStructures.Storage requireAccel(AcceleratorHandle handle) {
        VulkanAccelStructures.Storage storage = accelByHandle.get(handle.nativeHandle());
        if (storage == null) {
            throw new IllegalArgumentException(
                    tr("Acceleration structure does not belong to this provider or has been released: ") + handle.label());
        }
        return storage;
    }

    @Override
    public void recordBuildAccelerator(dev.luxloader.api.gpu.GpuCommands.CommandBuffer commands,
            AcceleratorHandle blas, dev.luxloader.api.gpu.AccelGeometry geometry) {
        accel().recordBlas(requireAccel(blas), geometry,
                ((VulkanCommands.CommandBufferImpl) commands).handle());
    }

    @Override
    public void recordBuildTopLevel(dev.luxloader.api.gpu.GpuCommands.CommandBuffer commands,
            AcceleratorHandle tlas, java.util.List<dev.luxloader.api.gpu.AccelInstance> instances) {
        accel().recordTopLevel(requireAccel(tlas), instances,
                ((VulkanCommands.CommandBufferImpl) commands).handle());
    }

    @Override
    public void updateAccelerator(AcceleratorHandle handle, AccelStructDesc desc) {
        VulkanAccelStructures.Storage storage = requireAccel(handle);
        if (desc.type() != storage.desc().type()) {
            throw new IllegalArgumentException(
                    tr("Cannot change acceleration structure type during update: ") + storage.desc().type() + " -> " + desc.type());
        }
        // This method validates descriptions only; geometry is supplied through buildAccelerator/buildTopLevel for actual rebuilding.
        throw new UnsupportedOperationException(
                tr("updateAccelerator cannot express geometry changes (AccelStructDesc contains no geometry); ")
                        + tr("rebuild with buildAccelerator(...) or buildTopLevel(...) instead. ")
                        + tr("Storage and handles are reused without reallocation"));
    }

    /**
     * Current live GPU bytes, incremented on allocation and decremented on release using bytesByHandle.
     * Never use cumulative historical allocation for budget decisions.
     */
    @Override
    public long usedVramBytes() {
        return usedBytes + (accelStructures == null ? 0 : accelStructures.auxiliaryBytes());
    }

    /** Caller must wait for in-flight frames before a host resource stack is replaced. */
    public void invalidateBorrowedImages() {
        var borrowed = new java.util.HashSet<Long>();
        for (var iterator = imageViews.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (ownedImages.contains(entry.getKey().image())) continue;
            vkDestroyImageView(device.vkDevice(), entry.getValue(), null);
            ownedViews.remove(entry.getValue()); borrowed.add(entry.getKey().image()); iterator.remove();
        }
        for (long image : borrowed) device.forgetImage(image);
    }

    @Override
    public long vramBudgetBytes() {
        return budgetBytes;
    }

    /** Adjusts the budget for automatic quality reduction. */
    public void setBudget(long bytes) {
        this.budgetBytes = Math.max(0L, bytes);
    }

    @Override
    public void release(ImageHandle image) {
        // Forget layout state before destroying an image so recycled handles cannot inherit stale layouts.
        if (image != null && !image.isNull()) {
            ((VulkanCommands) device.commands()).forgetImage(image.bits());
        }
        if (image == null) {
            return;
        }
        imageCache.values().removeIf(h -> h.equals(image));
        if (image.kind() == ImageHandle.Kind.VK_IMAGE && image.bits() != 0L) {
            vkDestroyImage(device.vkDevice(), image.bits(), null);
            ownedImages.remove(image.bits());
            // Decrement live usage on release so budget checks do not count historical allocation.
            Long released = bytesByHandle.remove(image.bits());
            if (released != null) {
                usedBytes -= released;
            }
        }
    }

    @Override
    public void release(GpuDevice.Handle buffer) {
        if (buffer == null || buffer.isNull()) {
            return;
        }
        bufferCache.values().removeIf(h -> h.equals(buffer));
        for (var it = ownedBuffers.iterator(); it.hasNext(); ) {
            long[] pair = it.next();
            if (pair[0] == buffer.bits()) {
                vkDestroyBuffer(device.vkDevice(), pair[0], null);
                vkFreeMemory(device.vkDevice(), pair[1], null);
                // Decrement buffer usage on release, as for images.
                Long released = bytesByHandle.remove(pair[0]);
                if (released != null) {
                    usedBytes -= released;
                }
                // Same handle is also in ownedMemories -- remove it there too, otherwise a later
                // closeAll() frees it a second time.
                ownedMemories.remove(Long.valueOf(pair[1]));
                it.remove();
            }
        }
    }

    @Override
    public void release(AcceleratorHandle accel) {
        if (accel == null || !accel.supported()) {
            return;
        }
        VulkanAccelStructures.Storage storage = accelByHandle.remove(accel.nativeHandle());
        if (storage == null) {
            return;
        }
        Long bytes = accelBytesByHandle.remove(accel.nativeHandle());
        if (bytes != null) {
            usedBytes -= bytes;
        }
        try {
            accel().destroy(storage);
        } catch (RuntimeException e) {
            device.diagnostics().warn(tr("Acceleration structure destruction failed (continuing): ") + accel.label() + " -> " + e);
        }
    }

    /** Releases every resource created by this provider. */
    public void closeAll() {
        VkDevice vk = device.vkDevice();
        for (long view : new ArrayList<>(ownedViews)) {
            vkDestroyImageView(vk, view, null);
        }
        ownedViews.clear();
        imageViews.clear();
        for (long image : new ArrayList<>(ownedImages)) {
            vkDestroyImage(vk, image, null);
            // Must forget the layout too -- see VulkanDevice.forgetImage.
            device.forgetImage(image);
        }
        ownedImages.clear();
        for (long[] pair : new ArrayList<>(ownedBuffers)) {
            vkDestroyBuffer(vk, pair[0], null);
            vkFreeMemory(vk, pair[1], null);
            // The same memory handle was also registered in ownedMemories (see
            // createBufferInternal). Without this removal the ownedMemories sweep frees
            // it a second time.
            ownedMemories.remove(Long.valueOf(pair[1]));
        }
        ownedBuffers.clear();
        for (long memory : new ArrayList<>(ownedMemories)) {
            vkFreeMemory(vk, memory, null);
        }
        ownedMemories.clear();
        // Acceleration structures own separate storage outside ownedBuffers; release it explicitly to prevent switch-time BVH leaks.
        for (VulkanAccelStructures.Storage storage : new ArrayList<>(accelByHandle.values())) {
            try {
                accel().destroy(storage);
            } catch (RuntimeException e) {
                device.diagnostics().warn(tr("Acceleration structure destruction failed (continuing): ") + storage.desc().name()
                        + " -> " + e);
            }
        }
        accelByHandle.clear();
        accelBytesByHandle.clear();
        imageCache.clear();
        bufferCache.clear();
        usedBytes = 0;
        bytesByHandle.clear();
    }

    @Override
    public void close() {
        closeAll();
    }

    /** Scope name for diagnostics. */
    public String scopeName() {
        return scopeName;
    }

    // Internal implementation.

    /**
     * Returns all participating queue families for CONCURRENT sharing, or null for single-family EXCLUSIVE
     * sharing. This avoids ownership transfers that current barriers do not express. Concurrent barriers
     * use QUEUE_FAMILY_IGNORED; listing all possible families is conservative because usage is not known
     * at creation. CONCURRENT requires more than one family (VUID 00942).
     */
    private java.nio.IntBuffer sharingQueueFamilies(MemoryStack stack) {
        java.util.LinkedHashSet<Integer> families = new java.util.LinkedHashSet<>();
        families.add(device.graphicsQueue().familyIndex());
        families.add(device.computeQueue().familyIndex());
        families.add(device.transferQueue().familyIndex());
        if (families.size() <= 1) {
            return null;
        }
        // Queue-family indices are uint32 values represented by IntBuffer, not LongBuffer.
        java.nio.IntBuffer buffer = stack.mallocInt(families.size());
        for (int family : families) {
            buffer.put(family);
        }
        buffer.flip();
        return buffer;
    }

    private ImageHandle createImageInternal(ImageDesc desc, boolean shared) {
        int vkFormat = desc.format().vkFormat();
        if (vkFormat < 0) {
            throw new IllegalArgumentException(tr("Cannot create texture with undefined format: ") + desc);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Concurrent sharing permits graphics/compute access; see sharingQueueFamilies.
            java.nio.IntBuffer sharingFamilies = sharingQueueFamilies(stack);

            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(vkFormat)
                    .mipLevels(desc.mips())
                    .arrayLayers(desc.layers())
                    .samples(desc.samples())
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .sharingMode(sharingFamilies == null
                            ? VK_SHARING_MODE_EXCLUSIVE : VK_SHARING_MODE_CONCURRENT)
                    .pQueueFamilyIndices(sharingFamilies)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(desc.width(), desc.height(), 1);

            int usage = 0;
            if (desc.has(ImageDesc.Usage.COLOR_ATTACHMENT)) {
                usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            }
            if (desc.has(ImageDesc.Usage.DEPTH_STENCIL_ATTACHMENT)) {
                usage |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
            }
            if (desc.has(ImageDesc.Usage.SAMPLED)) {
                usage |= VK_IMAGE_USAGE_SAMPLED_BIT;
            }
            if (desc.has(ImageDesc.Usage.STORAGE)) {
                usage |= VK_IMAGE_USAGE_STORAGE_BIT;
            }
            if (desc.has(ImageDesc.Usage.TRANSFER_SRC)) {
                usage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            }
            if (desc.has(ImageDesc.Usage.TRANSFER_DST)) {
                usage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            }
            if (usage == 0) {
                usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            }
            info.usage(usage);

            java.nio.LongBuffer pImage = stack.mallocLong(1);
            int err = vkCreateImage(device.vkDevice(), info, null, pImage);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(
                        tr("vkCreateImage failed: ") + VulkanDevice.resultName(err) + " (" + desc + ")");
            }
            long image = pImage.get(0);
            if (Boolean.getBoolean("luxloader.debug.vulkan")) {
                // Print the RAW handle vkCreateImage handed back, before anything else touches
                // it. Needed because our own traces show image handles of the shape
                // 0xNN00000000NN while api_dump shows pointer-shaped values for the very same
                // barrier structs -- two shapes that cannot both be the raw VkImage. Whichever
                // side is wrong decides whether the validation layer is even being told about
                // the right image.
                System.out.println("[VK] vkCreateImage -> raw=0x" + Long.toHexString(image)
                        + " label=" + scopeName + "/" + desc.name());
            }
            long memory = allocateForImage(image, desc);
            ownedImages.add(image);
            String label = scopeName + "/" + desc.name();
            long imageBytes = (long) desc.width() * desc.height()
                    * Math.max(1, desc.format().bytesPerPixel()) * Math.max(1, desc.layers());
            usedBytes += imageBytes;
            // Track allocation sizes for subtraction on release.
            bytesByHandle.put(image, imageBytes);
            device.onResourceCreated("image", label, usedBytes);
            return new ImageHandle(ImageHandle.Kind.VK_IMAGE, image, label);
        }
    }

    private long allocateForImage(long image, ImageDesc desc) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device.vkDevice(), image, req);
            int typeIndex = findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(req.size())
                    .memoryTypeIndex(typeIndex);
            java.nio.LongBuffer pMem = stack.mallocLong(1);
            int err = vkAllocateMemory(device.vkDevice(), alloc, null, pMem);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(
                        tr("Failed to allocate texture memory: ") + VulkanDevice.resultName(err)
                                + tr(" (requires ") + (req.size() >> 20) + tr(" MiB, type bits 0x")
                                + Integer.toHexString(req.memoryTypeBits()) + "）");
            }
            long memory = pMem.get(0);
            vkBindImageMemory(device.vkDevice(), image, memory, 0);
            ownedMemories.add(memory);
            return memory;
        }
    }

    private GpuDevice.Handle createBufferInternal(BufferDesc desc) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int usage = 0;
            if (desc.has(BufferDesc.Usage.VERTEX)) {
                usage |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
            }
            if (desc.has(BufferDesc.Usage.INDEX)) {
                usage |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
            }
            if (desc.has(BufferDesc.Usage.INDIRECT)) {
                usage |= VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
            }
            if (desc.has(BufferDesc.Usage.UNIFORM)) {
                usage |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
            }
            if (desc.has(BufferDesc.Usage.STORAGE)) {
                usage |= VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            }
            if (desc.has(BufferDesc.Usage.TRANSFER_SRC)) {
                usage |= VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
            }
            if (desc.has(BufferDesc.Usage.TRANSFER_DST)) {
                usage |= VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            }
            // Translate acceleration-structure build/storage/address usages into real Vulkan flags; an API declaration alone cannot make an ordinary storage buffer valid build input.
            boolean needsDeviceAddress = false;
            if (desc.has(BufferDesc.Usage.ACCEL_STRUCT_BUILD_INPUT)
                    || desc.has(BufferDesc.Usage.ACCEL_STRUCT_INSTANCE)) {
                // Instance data is acceleration-structure build input, like vertices, indices and AABBs.
                usage |= org.lwjgl.vulkan.KHRAccelerationStructure
                        .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
                needsDeviceAddress = true;
            }
            if (desc.has(BufferDesc.Usage.ACCEL_STRUCT_STORAGE)) {
                usage |= org.lwjgl.vulkan.KHRAccelerationStructure
                        .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
                needsDeviceAddress = true;
            }
            if (desc.has(BufferDesc.Usage.SHADER_BINDING_TABLE)) {
                usage |= org.lwjgl.vulkan.KHRRayTracingPipeline
                        .VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;
                needsDeviceAddress = true;
            }
            if (needsDeviceAddress) {
                // These buffers require SHADER_DEVICE_ADDRESS for valid vkGetBufferDeviceAddress results.
                usage |= org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
            }
            if (usage == 0) {
                usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            }

            // Buffers also need sharing across graphics and compute families.
            java.nio.IntBuffer bufferFamilies = sharingQueueFamilies(stack);

            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(desc.size())
                    .usage(usage)
                    .sharingMode(bufferFamilies == null
                            ? VK_SHARING_MODE_EXCLUSIVE : VK_SHARING_MODE_CONCURRENT)
                    .pQueueFamilyIndices(bufferFamilies);

            java.nio.LongBuffer pBuf = stack.mallocLong(1);
            int err = vkCreateBuffer(device.vkDevice(), info, null, pBuf);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(tr("vkCreateBuffer failed: ") + VulkanDevice.resultName(err));
            }
            long buffer = pBuf.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device.vkDevice(), buffer, req);
            int props = desc.hostVisible()
                    ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            int typeIndex;
            try {
                typeIndex = findMemoryType(req.memoryTypeBits(), props);
            } catch (RuntimeException e) {
                // Include buffer name, size, usage and host visibility in allocation failures so concurrent vertex/index/material/BVH allocations can be distinguished.
                throw new dev.luxloader.core.util.LuxException(tr("Buffer ") + desc.name()
                        + "（" + desc.size() + tr(" bytes, usage=0x") + Integer.toHexString(usage)
                        + "，hostVisible=" + desc.hostVisible() + "，reqSize=" + req.size()
                        + tr(") allocation failed: ") + e.getMessage());
            }
            // Device-address usage requires the DEVICE_ADDRESS allocation flag; omission may surface only when querying the address.
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(req.size())
                    .memoryTypeIndex(typeIndex);
            if ((usage & org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
                alloc.pNext(org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                        .flags(org.lwjgl.vulkan.VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT)
                        .address());
            }
            java.nio.LongBuffer pMem = stack.mallocLong(1);
            err = vkAllocateMemory(device.vkDevice(), alloc, null, pMem);
            if (err != VK_SUCCESS) {
                throw new dev.luxloader.core.util.LuxException(tr("Failed to allocate buffer memory: ") + VulkanDevice.resultName(err));
            }
            long memory = pMem.get(0);
            vkBindBufferMemory(device.vkDevice(), buffer, memory, 0);

            ownedBuffers.add(new long[] {buffer, memory});
            ownedMemories.add(memory);
            usedBytes += req.size();
            bytesByHandle.put(buffer, req.size());
            String label = scopeName + "/" + desc.name();
            device.onResourceCreated("buffer", label, usedBytes);
            return new GpuDevice.Handle(GpuDevice.Handle.Kind.VK_BUFFER, buffer, desc.size(), label);
        }
    }

    private int findMemoryType(int typeBits, int required) {
        VkPhysicalDeviceMemoryProperties mem = device.memoryProperties();
        for (int i = 0; i < mem.memoryTypeCount(); i++) {
            boolean typeOk = (typeBits & (1 << i)) != 0;
            boolean propsOk = (mem.memoryTypes(i).propertyFlags() & required) == required;
            if (typeOk && propsOk) {
                return i;
            }
        }
        // Fallback to matching memory type bits when preferred properties are unavailable.
        for (int i = 0; i < mem.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0) {
                return i;
            }
        }
        throw new dev.luxloader.core.util.LuxException(
                tr("No usable GPU memory type (typeBits=0x") + Integer.toHexString(typeBits)
                        + ", required=0x" + Integer.toHexString(required) + "）");
    }

    /** Cached image count. */
    public int imageCount() {
        return imageCache.size();
    }

    /** Cached buffer count. */
    public int bufferCount() {
        return bufferCache.size();
    }

    /** Created image view count. */
    public int viewCount() {
        return imageViews.size();
    }

    /**
     * Resolves/caches a VkImageView by image and view description, retaining ownership and avoiding
     * per-frame view leaks.
     * @param image image handle
     * @param view optional view description, null for full range
     * @return view handle, or zero on failure
     */
    /**
     * Looks up dimensions/format by image handle for valid blit extents.
     * @param image handle
     * @return description, or null for external/released images
     */
    public ImageDesc describe(ImageHandle image) {
        if (image == null || image.isNull()) {
            return null;
        }
        for (Map.Entry<ImageDesc, ImageHandle> e : imageCache.entrySet()) {
            if (e.getValue().bits() == image.bits()) {
                return e.getKey();
            }
        }
        return null;
    }

    @Override
    public java.util.Optional<dev.luxloader.api.pipeline.GpuResourceProvider.ImageInfo> imageInfo(
            ImageHandle image) {
        ImageDesc description = describe(image);
        if (description == null) {
            return java.util.Optional.empty();
        }
        var ownership = ownedImages.contains(image.bits())
                ? dev.luxloader.api.pipeline.GpuResourceProvider.Ownership.OWNED
                : description.has(ImageDesc.Usage.EXTERNAL)
                    ? dev.luxloader.api.pipeline.GpuResourceProvider.Ownership.SHARED
                    : dev.luxloader.api.pipeline.GpuResourceProvider.Ownership.BORROWED;
        return java.util.Optional.of(new dev.luxloader.api.pipeline.GpuResourceProvider.ImageInfo(
                description, ownership, scopeName, 0L));
    }

    @Override
    public long nativeImageView(ImageHandle image, dev.luxloader.api.gpu.ImageViewDesc view) {
        boolean knownFormat = describe(image) != null
                || (view != null && view.format() != null)
                || (device.commands() instanceof VulkanCommands vkCommands
                && image != null && vkCommands.externalFormat(image.bits()) > 0);
        if (!knownFormat) {
            throw new IllegalArgumentException("Native image view needs a measured VkFormat: " + image);
        }
        long handle = resolveView(image, view);
        if (handle == 0L) {
            throw new IllegalArgumentException("Cannot resolve a native view for " + image);
        }
        return handle;
    }

    public long resolveView(ImageHandle image, dev.luxloader.api.gpu.ImageViewDesc view) {
        if (image == null || image.isNull() || image.kind() != ImageHandle.Kind.VK_IMAGE) {
            return 0L;
        }
        dev.luxloader.api.gpu.ImageViewDesc key =
                view == null ? dev.luxloader.api.gpu.ImageViewDesc.full() : view;
        ViewKey cacheKey = new ViewKey(image.bits(), key);
        Long cached = imageViews.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        long created = createImageView(image, key);
        if (created != 0L) {
            imageViews.put(cacheKey, created);
        }
        return created;
    }

    private long createImageView(ImageHandle image, dev.luxloader.api.gpu.ImageViewDesc view) {
        // Recover the format from cached descriptions; if unavailable, report the fallback to a 2D color view.
        ImageDesc desc = null;
        for (Map.Entry<ImageDesc, ImageHandle> e : imageCache.entrySet()) {
            if (e.getValue().bits() == image.bits()) {
                desc = e.getKey();
                break;
            }
        }
        // Host-owned images carry their real VkFormat, measured by the access layer. Prefer it:
        // for an image we did not create there is no cache entry, and guessing a format here is
        // exactly how a bogus view got built in the past.
        int externalFormat = 0;
        if (device.commands() instanceof VulkanCommands vkCommands) {
            externalFormat = vkCommands.externalFormat(image.bits());
        }
        int fallbackFormat = dev.luxloader.api.gpu.GpuFormat.R8G8B8A8_UNORM.vkFormat();
        int format = externalFormat > 0 ? externalFormat
                : (view.format() != null ? view.format().vkFormat()
                : (desc != null ? desc.format().vkFormat() : fallbackFormat));
        if (format < 0) {
            format = fallbackFormat;
        }
        boolean depthImage = (externalFormat > 0
                && dev.luxloader.api.gpu.GpuFormat.fromVk(externalFormat).isDepth())
                || (desc != null && desc.format().isDepth())
                || (view.format() != null && view.format().isDepth());
        int aspect = depthImage
                ? VK_IMAGE_ASPECT_DEPTH_BIT
                : VK_IMAGE_ASPECT_COLOR_BIT;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkImageViewCreateInfo info =
                    org.lwjgl.vulkan.VkImageViewCreateInfo.calloc(stack)
                            .sType$Default()
                            .image(image.bits())
                            .viewType(VK_IMAGE_VIEW_TYPE_2D)
                            .format(format);
            info.subresourceRange()
                    .aspectMask(aspect)
                    .baseMipLevel(view.baseMip())
                    .levelCount(view.mipCount() > 0 ? view.mipCount() : VK_REMAINING_MIP_LEVELS)
                    .baseArrayLayer(view.baseLayer())
                    .layerCount(view.layerCount() > 0 ? view.layerCount() : VK_REMAINING_ARRAY_LAYERS);
            java.nio.LongBuffer pView = stack.mallocLong(1);
            int err = vkCreateImageView(device.vkDevice(), info, null, pView);
            if (err != VK_SUCCESS) {
                device.diagnostics().warn(tr("Failed to create image view: ") + VulkanDevice.resultName(err)
                        + "（image=0x" + Long.toHexString(image.bits()) + ", format=" + format + "）");
                return 0L;
            }
            long handle = pView.get(0);
            ownedViews.add(handle);
            return handle;
        }
    }
}
