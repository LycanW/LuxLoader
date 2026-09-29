package dev.luxloader.core.vulkan;

import static dev.luxloader.api.i18n.Messages.tr;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

import dev.luxloader.api.gpu.AccelGeometry;
import dev.luxloader.api.gpu.AccelInstance;
import dev.luxloader.api.gpu.AccelStructDesc;
import dev.luxloader.api.gpu.AcceleratorHandle;
import dev.luxloader.core.util.LuxException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryInstancesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryTrianglesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

/**
 * Vulkan acceleration-structure allocation/build operations separated from provider budgeting/caching.
 * Builds submit and wait synchronously; avoid per-frame hot paths. Addressable device-local buffers
 * require SHADER_DEVICE_ADDRESS usage and VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT allocation flags.
 */
final class VulkanAccelStructures {

    private final VulkanDevice device;
    private long scratchAlignment;
    private final java.util.Map<Long, long[]> scratchBuffers = new java.util.HashMap<>();
    private final java.util.Map<Long, long[]> instanceBuffers = new java.util.HashMap<>();

    long auxiliaryBytes() {
        return java.util.stream.Stream.concat(scratchBuffers.values().stream(), instanceBuffers.values().stream())
                .mapToLong(allocation -> allocation[2]).sum();
    }

    VulkanAccelStructures(VulkanDevice device) {
        this.device = device;
    }

    /**
     * Allocated acceleration structure.
     * @param handle plugin-facing handle
     * @param accel VkAccelerationStructureKHR
     * @param buffer backing storage
     * @param memory backing allocation
     * @param address device address
     * @param size storage bytes
     * @param desc declared type and build limits
     */
    record Storage(AcceleratorHandle handle, long accel, long buffer, long memory,
                   long address, long size, long buildScratchSize, AccelStructDesc desc) {
    }

    /**
     * Allocates storage/object without geometry, using vkGetAccelerationStructureBuildSizesKHR rather than
     * guessed BVH sizes.
     */
    Storage create(AccelStructDesc desc) {
        VkDevice vk = device.vkDevice();
        try (MemoryStack stack = stackPush()) {
            long geometrySize = geometryStorageBytes(desc);
            int geometryUsage = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                    | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

            // Size queries must include geometry type, vertex format/stride and maximum primitive count. Omitting geometry produced empty-structure sizing and later out-of-bounds BVH writes/device loss. Query addresses may remain zero because sizing does not dereference them.
            VkAccelerationStructureGeometryKHR.Buffer queryGeometry =
                    VkAccelerationStructureGeometryKHR.calloc(1, stack);
            queryGeometry.get(0).sType$Default();
            if (desc.type() == AccelStructDesc.Type.TOP_LEVEL) {
                queryGeometry.get(0)
                        .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                        .flags(0)
                        .geometry().instances(VkAccelerationStructureGeometryInstancesDataKHR
                                .calloc(stack).sType$Default().arrayOfPointers(false));
            } else {
                AccelStructDesc.BlasLayout layout = desc.blasLayout();
                queryGeometry.get(0)
                        .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                        .flags(layout.opaque() ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0)
                        .geometry().triangles(VkAccelerationStructureGeometryTrianglesDataKHR
                                .calloc(stack).sType$Default()
                                .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                                .vertexStride(layout.vertexStride())
                                .maxVertex(Math.max(0, desc.maxVertexCount() - 1))
                                .indexType(layout.indexType() == AccelGeometry.IndexType.UINT16
                                        ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32));
            }

            VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType$Default()
                            .type(desc.type() == AccelStructDesc.Type.TOP_LEVEL
                                    ? VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR
                                    : VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                            .flags(desc.isDynamic()
                                    ? VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR
                                    : VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .geometryCount(1)
                            .pGeometries(queryGeometry);

            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            var maxCounts = stack.ints(primitiveCount(desc));
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo, maxCounts, sizes);

            long size = sizes.accelerationStructureSize();
            if (size <= 0L) {
                throw new LuxException(tr("Acceleration structure size is zero: ") + desc.name() + tr(" (was the geometry count declared as zero?)"));
            }

            long[] bufferAndMemory = createBuffer(size, geometryUsage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            long buffer = bufferAndMemory[0];
            long memory = bufferAndMemory[1];
            boolean ok = false;
            try {
                VkAccelerationStructureCreateInfoKHR createInfo =
                        VkAccelerationStructureCreateInfoKHR.calloc(stack)
                                .sType$Default()
                                .buffer(buffer)
                                .offset(0L)
                                .size(size)
                                .type(desc.type() == AccelStructDesc.Type.TOP_LEVEL
                                        ? VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR
                                        : VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                LongBuffer pAccel = stack.mallocLong(1);
                int err = vkCreateAccelerationStructureKHR(vk, createInfo, null, pAccel);
                if (err != VK_SUCCESS) {
                    throw new LuxException(tr("Failed to create acceleration structure: ") + desc.name() + " -> "
                            + VulkanDevice.resultName(err));
                }
                long accel = pAccel.get(0);

                VkAccelerationStructureDeviceAddressInfoKHR addrInfo =
                        VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                                .sType$Default()
                                .accelerationStructure(accel);
                long address = vkGetAccelerationStructureDeviceAddressKHR(vk, addrInfo);

                AcceleratorHandle handle = AcceleratorHandle.of(desc.type(), accel, address, size,
                        desc.name());
                ok = true;
                return new Storage(handle, accel, buffer, memory, address, size, sizes.buildScratchSize(), desc);
            } finally {
                if (!ok) {
                    vkDestroyBuffer(vk, buffer, null);
                    vkFreeMemory(vk, memory, null);
                }
            }
        }
    }

    /** Builds a triangle BLAS. */
    void buildBlas(Storage storage, AccelGeometry geometry) {
        recordBlas(storage, geometry, null);
    }

    void recordBlas(Storage storage, AccelGeometry geometry, org.lwjgl.vulkan.VkCommandBuffer commands) {
        if (storage.desc().type() != AccelStructDesc.Type.BOTTOM_LEVEL) {
            throw new LuxException(tr("buildBlas received a top-level structure: ") + storage.desc().name());
        }
        if (geometry.triangleCount() > storage.desc().triangleCount()) {
            throw new LuxException(tr("Triangle count exceeds declared capacity: ") + geometry.triangleCount()
                    + " > " + storage.desc().triangleCount() + "（" + storage.desc().name() + "）");
        }
        AccelStructDesc.BlasLayout layout = storage.desc().blasLayout();
        if (geometry.vertexCount() > storage.desc().maxVertexCount()
                || geometry.vertexStride() != layout.vertexStride()
                || geometry.indexType() != layout.indexType()
                || geometry.opaque() != layout.opaque()) {
            throw new LuxException(tr("BLAS build layout differs from the size-query layout: ")
                    + storage.desc().name());
        }
        VkDevice vk = device.vkDevice();
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureGeometryTrianglesDataKHR triangles =
                    VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack)
                            .sType$Default()
                            .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                            .vertexData(org.lwjgl.vulkan.VkDeviceOrHostAddressConstKHR.calloc(stack)
                                    .deviceAddress(bufferAddress(geometry.vertexBuffer().bits())
                                            + geometry.vertexOffset()))
                            .vertexStride(geometry.vertexStride())
                            .maxVertex(storage.desc().maxVertexCount() - 1)
                            .indexType(geometry.indexType() == AccelGeometry.IndexType.UINT16
                                    ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32)
                            .indexData(org.lwjgl.vulkan.VkDeviceOrHostAddressConstKHR.calloc(stack)
                                    .deviceAddress(bufferAddress(geometry.indexBuffer().bits())
                                            + geometry.indexOffset()))
                            .transformData(org.lwjgl.vulkan.VkDeviceOrHostAddressConstKHR.calloc(stack)
                                    .deviceAddress(0L));

            VkAccelerationStructureGeometryKHR.Buffer geometries =
                    VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometries.get(0).sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(geometry.opaque() ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0)
                    .geometry().triangles(triangles);

            int[] maxCounts = {geometry.triangleCount()};
            recordBuild(storage, geometries, maxCounts, maxCounts, commands);
        }
    }

    void recordTopLevel(Storage storage, List<AccelInstance> instances,
                        org.lwjgl.vulkan.VkCommandBuffer commands) {
        if (storage.desc().type() != AccelStructDesc.Type.TOP_LEVEL
                || instances.size() > storage.desc().instanceCount()) {
            throw new IllegalArgumentException("Invalid recorded TLAS build: " + storage.desc().name());
        }
        long[] buffer = instanceBuffers.computeIfAbsent(storage.accel(), ignored ->
                createBuffer(Math.max(1, storage.desc().instanceCount()) * 64L,
                        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
        buildBarrier(commands);
        if (!instances.isEmpty()) {
            ByteBuffer data = org.lwjgl.system.MemoryUtil.memCalloc(instances.size() * 64);
            try {
                var entries = VkAccelerationStructureInstanceKHR.create(
                        org.lwjgl.system.MemoryUtil.memAddress(data), instances.size());
                for (int n = 0; n < instances.size(); n++) {
                    AccelInstance instance = instances.get(n);
                    var entry = entries.get(n);
                    float[] transform = instance.transform();
                    for (int j = 0; j < 12; j++) entry.transform().matrix(j, transform[j]);
                    entry.instanceCustomIndex(instance.instanceId()).mask(instance.mask())
                            .instanceShaderBindingTableRecordOffset(instance.hitGroupIndex())
                            .flags(instance.opaque() ? VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR : 0)
                            .accelerationStructureReference(instance.blas().deviceAddress());
                }
                for (int offset = 0; offset < data.capacity(); offset += 65536) {
                    int count = Math.min(65536, data.capacity() - offset);
                    vkCmdUpdateBuffer(commands, buffer[0], offset, data.slice(offset, count));
                }
            } finally { org.lwjgl.system.MemoryUtil.memFree(data); }
        }
        try (MemoryStack stack = stackPush()) {
            var geometries = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometries.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .geometry().instances(VkAccelerationStructureGeometryInstancesDataKHR.calloc(stack)
                            .sType$Default().arrayOfPointers(false)
                            .data(org.lwjgl.vulkan.VkDeviceOrHostAddressConstKHR.calloc(stack)
                                    .deviceAddress(bufferAddress(buffer[0]))));
            recordBuild(storage, geometries, new int[]{Math.max(1, storage.desc().instanceCount())},
                    new int[]{instances.size()}, commands);
        }
    }

    /** Builds a TLAS from instances. */
    void buildTopLevel(Storage storage, List<AccelInstance> instances) {
        if (storage.desc().type() != AccelStructDesc.Type.TOP_LEVEL) {
            throw new LuxException(tr("buildTopLevel received a bottom-level structure: ") + storage.desc().name());
        }
        if (instances.size() > storage.desc().instanceCount()) {
            throw new LuxException(tr("Instance count exceeds declared capacity: ") + instances.size()
                    + " > " + storage.desc().instanceCount() + "（" + storage.desc().name() + "）");
        }
        VkDevice vk = device.vkDevice();
        try (MemoryStack stack = stackPush()) {
            // Allocate a valid instance buffer even for zero instances; Vulkan forbids zero-sized buffers.
            int stride = VkAccelerationStructureInstanceKHR.SIZEOF;
            long bytes = Math.max(1, (long) stride * Math.max(1, instances.size()));
            // CPU-written instance data needs host-visible memory; mapping a device-only allocation fails.
            long[] instBufMem = createBuffer(bytes, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                    | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            long instBuffer = instBufMem[0];
            long instMemory = instBufMem[1];
            boolean ok = false;
            try {
                if (!instances.isEmpty()) {
                    ByteBuffer mapped = mapMemory(instMemory, bytes);
                    VkAccelerationStructureInstanceKHR.Buffer arr =
                            VkAccelerationStructureInstanceKHR.create(
                                    org.lwjgl.system.MemoryUtil.memAddress(mapped), instances.size());
                    for (int i = 0; i < instances.size(); i++) {
                        AccelInstance in = instances.get(i);
                        float[] m = in.transform();
                        arr.get(i).transform()
                                .matrix(0, m[0]).matrix(1, m[1]).matrix(2, m[2]).matrix(3, m[3])
                                .matrix(4, m[4]).matrix(5, m[5]).matrix(6, m[6]).matrix(7, m[7])
                                .matrix(8, m[8]).matrix(9, m[9]).matrix(10, m[10]).matrix(11, m[11]);
                        arr.get(i).instanceCustomIndex(in.instanceId())
                                .mask(in.mask())
                                .instanceShaderBindingTableRecordOffset(in.hitGroupIndex())
                                .flags(in.opaque() ? VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR : 0)
                                .accelerationStructureReference(in.blas().deviceAddress());
                    }
                    unmapMemory(instMemory);
                }

                VkAccelerationStructureGeometryInstancesDataKHR instancesData =
                        VkAccelerationStructureGeometryInstancesDataKHR.calloc(stack)
                                .sType$Default()
                                .arrayOfPointers(false)
                                .data(org.lwjgl.vulkan.VkDeviceOrHostAddressConstKHR.calloc(stack)
                                        .deviceAddress(bufferAddress(instBuffer)));

                VkAccelerationStructureGeometryKHR.Buffer geometries =
                        VkAccelerationStructureGeometryKHR.calloc(1, stack);
                geometries.get(0).sType$Default()
                        .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                        .flags(0)
                        .geometry().instances(instancesData);

                int[] maxCounts = {Math.max(1, instances.size())};
                recordBuild(storage, geometries, maxCounts, new int[]{instances.size()});
                ok = true;
            } finally {
                // Reclaim the temporary instance buffer after submission completion.
                vkDestroyBuffer(vk, instBuffer, null);
                vkFreeMemory(vk, instMemory, null);
                if (!ok) {
                    // No additional resources need cleanup on failure.
                }
            }
        }
    }

    /** Records, submits and waits for one build command. */
    private void recordBuild(Storage storage, VkAccelerationStructureGeometryKHR.Buffer geometries,
                             int[] maxCounts, int[] counts) {
        recordBuild(storage, geometries, maxCounts, counts, null);
    }

    private void recordBuild(Storage storage, VkAccelerationStructureGeometryKHR.Buffer geometries,
                             int[] maxCounts, int[] counts, org.lwjgl.vulkan.VkCommandBuffer recording) {
        VkDevice vk = device.vkDevice();
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType$Default()
                            .type(storage.desc().type() == AccelStructDesc.Type.TOP_LEVEL
                                    ? VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR
                                    : VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                            .flags(storage.desc().isDynamic()
                                    ? VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR
                                    : VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .dstAccelerationStructure(storage.accel())
                            .geometryCount(1)
                            .pGeometries(geometries);

            // The creation query uses the declared maximum geometry/primitive counts.
            // Vulkan guarantees those sizes cover all builds within that capacity.
            // Smaller-count queries may pick a different build algorithm and return
            // a larger scratch estimate; comparing them to this allocation is invalid.
            // Geometry layout and counts have already been checked by recordBlas/TLAS.
            if (scratchAlignment == 0) {
                var asProperties = org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR
                        .calloc(stack).sType$Default();
                var properties = org.lwjgl.vulkan.VkPhysicalDeviceProperties2.calloc(stack)
                        .sType$Default().pNext(asProperties.address());
                org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2(vk.getPhysicalDevice(), properties);
                scratchAlignment = Math.max(1L, asProperties.minAccelerationStructureScratchOffsetAlignment());
            }
            long alignment = scratchAlignment;
            long scratchSize = Math.addExact(Math.max(1L, storage.buildScratchSize()), alignment - 1);
            long[] scratchBufMem = scratchBuffers.get(storage.accel());
            if (scratchBufMem == null) {
                long allocationStart = System.nanoTime();
                scratchBufMem = createBuffer(scratchSize,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                scratchBuffers.put(storage.accel(), scratchBufMem);
                if (device.diagnostics() != null) device.diagnostics().metric("cpu.accel.scratch-allocation",
                        (System.nanoTime() - allocationStart) / 1_000_000.0, "ms");
            }
            long scratchBuffer = scratchBufMem[0];
            buildInfo.scratchData(org.lwjgl.vulkan.VkDeviceOrHostAddressKHR.calloc(stack)
                    .deviceAddress((bufferAddress(scratchBuffer) + alignment - 1) / alignment * alignment));

            VulkanCommands.CommandBufferImpl cmd =
                    recording == null ? (VulkanCommands.CommandBufferImpl) device.commands().begin("accel-build") : null;
            var handle = recording == null ? cmd.handle() : recording;
            buildBarrier(handle);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            ranges.get(0).primitiveCount(counts[0]).primitiveOffset(0).firstVertex(0)
                    .transformOffset(0);
            PointerBuffer ppRanges = stack.mallocPointer(1);
            ppRanges.put(0, ranges.address());
            long recordStart = System.nanoTime();
            vkCmdBuildAccelerationStructuresKHR(handle,
                    VkAccelerationStructureBuildGeometryInfoKHR.create(buildInfo.address(), 1),
                    ppRanges);
            if (device.diagnostics() != null) device.diagnostics().metric("cpu.accel.native-build-record",
                    (System.nanoTime() - recordStart) / 1_000_000.0, "ms");
            buildBarrier(handle);
            if (cmd != null) {
                cmd.end();
                ((VulkanCommands) device.commands()).submitAndWait(cmd, device.graphicsQueue());
            }
        }
    }

    void destroy(Storage storage) {
        VkDevice vk = device.vkDevice();
        for (var buffers : java.util.List.of(scratchBuffers, instanceBuffers)) {
            long[] allocation = buffers.remove(storage.accel());
            if (allocation != null) {
                vkDestroyBuffer(vk, allocation[0], null);
                vkFreeMemory(vk, allocation[1], null);
            }
        }
        vkDestroyAccelerationStructureKHR(vk, storage.accel(), null);
        vkDestroyBuffer(vk, storage.buffer(), null);
        vkFreeMemory(vk, storage.memory(), null);
    }

    private static void buildBarrier(org.lwjgl.vulkan.VkCommandBuffer commands) {
        try (MemoryStack stack = stackPush()) {
            var barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
        }
    }

    // Low-level helpers.

    /** Maximum primitive count used by build-size queries. */
    private static int primitiveCount(AccelStructDesc desc) {
        return desc.type() == AccelStructDesc.Type.TOP_LEVEL
                ? Math.max(1, desc.instanceCount())
                : Math.max(1, desc.triangleCount());
    }

    /** Worst-case declared geometry bytes for diagnostics, not allocation. */
    private static long geometryStorageBytes(AccelStructDesc desc) {
        return desc.type() == AccelStructDesc.Type.TOP_LEVEL
                ? (long) desc.instanceCount() * VkAccelerationStructureInstanceKHR.SIZEOF
                : (long) desc.maxVertexCount() * 12L;
    }

    private long bufferAddress(long buffer) {
        try (MemoryStack stack = stackPush()) {
            VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType$Default().buffer(buffer);
            return vkGetBufferDeviceAddress(device.vkDevice(), info);
        }
    }

    private long[] createBuffer(long size, int usage, int memoryProps) {
        VkDevice vk = device.vkDevice();
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage)
                    // Exclusive sharing is valid because builds use only the graphics family.
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            int err = vkCreateBuffer(vk, info, null, pBuf);
            if (err != VK_SUCCESS) {
                throw new LuxException(tr("Failed to create acceleration structure buffer: ") + VulkanDevice.resultName(err));
            }
            long buffer = pBuf.get(0);
            boolean ok = false;
            try {
                VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(vk, buffer, req);
                int typeIndex = findMemoryType(req.memoryTypeBits(), memoryProps);

                // SHADER_DEVICE_ADDRESS buffers require the DEVICE_ADDRESS allocation flag.
                VkMemoryAllocateFlagsInfo flags = VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                        .flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
                VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                        .sType$Default()
                        .pNext(flags.address())
                        .allocationSize(req.size())
                        .memoryTypeIndex(typeIndex);
                LongBuffer pMem = stack.mallocLong(1);
                err = vkAllocateMemory(vk, alloc, null, pMem);
                if (err != VK_SUCCESS) {
                    throw new LuxException(tr("Failed to allocate acceleration structure memory: ") + VulkanDevice.resultName(err));
                }
                long memory = pMem.get(0);
                vkBindBufferMemory(vk, buffer, memory, 0);
                ok = true;
                return new long[] {buffer, memory, req.size()};
            } finally {
                if (!ok) {
                    vkDestroyBuffer(vk, buffer, null);
                }
            }
        }
    }

    private java.nio.ByteBuffer mapMemory(long memory, long size) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            int err = vkMapMemory(device.vkDevice(), memory, 0L, size, 0, p);
            if (err != VK_SUCCESS) {
                throw new LuxException(tr("Failed to map instance buffer: ") + VulkanDevice.resultName(err));
            }
            return org.lwjgl.system.MemoryUtil.memByteBuffer(p.get(0), (int) size);
        }
    }

    private void unmapMemory(long memory) {
        vkUnmapMemory(device.vkDevice(), memory);
    }

    private int findMemoryType(int typeBits, int required) {
        var mem = device.memoryProperties();
        for (int i = 0; i < mem.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                    && (mem.memoryTypes(i).propertyFlags() & required) == required) {
                return i;
            }
        }
        throw new LuxException(tr("No compatible GPU memory type: typeBits=0x")
                + Integer.toHexString(typeBits) + " required=0x" + Integer.toHexString(required));
    }
}
