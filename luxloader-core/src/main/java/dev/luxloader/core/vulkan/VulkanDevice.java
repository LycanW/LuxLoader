package dev.luxloader.core.vulkan;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.diag.Diagnostics;
import dev.luxloader.api.gpu.ComputePipelineDesc;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuCommands;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.gpu.GpuQueue;
import dev.luxloader.api.gpu.ImageDesc;
import dev.luxloader.api.gpu.ImageHandle;
import dev.luxloader.api.pipeline.GpuResourceProvider;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;

/**
 * Vulkan device implementation for owned standalone/offscreen devices and borrowed host devices. adopt
 * never transfers host destruction ownership. Report unavailable capabilities explicitly so plugins
 * can choose fallbacks.
 */
public final class VulkanDevice implements GpuDevice {

    private final VkDevice vkDevice;
    private final org.lwjgl.vulkan.VkPhysicalDevice physicalDevice;
    private final GpuCapabilities capabilities;
    private final Diagnostics diagnostics;
    private final boolean owned;
    private final VkPhysicalDeviceMemoryProperties memoryProperties;
    private final VulkanCommands commands;

    /**
     * Callback reporting native-path failures without coupling the device/command layer to persistent
     * device-profile storage.
     */
    private volatile java.util.function.BiConsumer<String, String> issueReporter;
    private final Map<Integer, VkQueue> queues = new LinkedHashMap<>();
    private final GpuQueue graphicsQueue;
    private final GpuQueue computeQueue;
    private final GpuQueue transferQueue;
    private final long nativeInstanceHandle;

    private final Deque<Runnable> pendingReadbacks = new ArrayDeque<>();
    private long resourceBytes;
    private long createdResources;

    /**
     * Centralized queue selection shared by creation and retrieval.
     * @param graphics graphics family
     * @param dedicatedCompute separate compute family, or -1 for graphics fallback
     * @param dedicatedTransfer separate transfer family, or -1 for graphics fallback
     */
    record QueueFamilies(int graphics, int dedicatedCompute, int dedicatedTransfer) {

        boolean hasAsyncCompute() {
            return dedicatedCompute >= 0;
        }

        int compute() {
            return dedicatedCompute >= 0 ? dedicatedCompute : graphics;
        }

        int transfer() {
            return dedicatedTransfer >= 0 ? dedicatedTransfer : graphics;
        }
    }

    /** Selects graphics/compute/transfer families from queue properties. */
    static QueueFamilies selectQueueFamilies(List<VulkanApi.QueueFamilyInfo> families) {
        int graphics = -1;
        int compute = -1;
        int transfer = -1;
        for (VulkanApi.QueueFamilyInfo family : families) {
            if (graphics < 0 && family.hasGraphics()) {
                graphics = family.index();
            }
            if (compute < 0 && family.isDedicatedCompute()) {
                compute = family.index();
            }
            if (transfer < 0 && family.isDedicatedTransfer()) {
                transfer = family.index();
            }
        }
        if (graphics < 0) {
            graphics = 0;
        }
        return new QueueFamilies(graphics, compute, transfer);
    }

    private VulkanDevice(VkDevice device, org.lwjgl.vulkan.VkPhysicalDevice physicalDevice,
                         GpuCapabilities capabilities, Diagnostics diagnostics, boolean owned,
                         long nativeInstanceHandle) {
        this.vkDevice = device;
        this.physicalDevice = physicalDevice;
        this.capabilities = capabilities;
        this.diagnostics = diagnostics;
        this.owned = owned;
        this.nativeInstanceHandle = nativeInstanceHandle;
        this.memoryProperties = VkPhysicalDeviceMemoryProperties.calloc();
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);

        // Prepare queues before commands because command-pool construction needs the graphics family.
        List<VulkanApi.QueueFamilyInfo> families = VulkanApi.queueFamilies(physicalDevice);
        QueueFamilies chosen = selectQueueFamilies(families);
        int graphicsFamily = chosen.graphics();
        int computeFamily = chosen.compute();
        int transferFamily = chosen.transfer();

        // Prefer dedicated compute; otherwise use graphics and report dedicated=false.
        VkQueue graphics = retrieveQueue(graphicsFamily, 0);
        VkQueue compute = computeFamily == graphicsFamily ? graphics : retrieveQueue(computeFamily, 0);
        VkQueue transfer = transferFamily == graphicsFamily ? graphics : retrieveQueue(transferFamily, 0);

        this.graphicsQueue = GpuQueue.of(graphicsFamily, 0, true, graphics.address(), "graphics");
        this.computeQueue = GpuQueue.of(computeFamily, 0, chosen.hasAsyncCompute(),
                compute.address(), "compute");
        this.transferQueue = GpuQueue.of(transferFamily, 0, chosen.dedicatedTransfer() >= 0,
                transfer.address(), "transfer");

        this.commands = new VulkanCommands(this);
        if (diagnostics != null && diagnostics.isVerbose()) {
            diagnostics.debug(tr("Queue families: graphics=") + graphicsFamily + " compute=" + computeFamily
                    + " transfer=" + transferFamily + tr(" total families=") + families.size());
        }
    }

    /**
     * Creates an instance, logical device and queues.
     * @param applicationName application name
     * @param preferDiscrete prefer discrete hardware
     * @param diagnostics diagnostic sink
     * @return device, or null on failure
     */
    public static VulkanDevice create(String applicationName, boolean preferDiscrete, Diagnostics diagnostics) {
        return create(applicationName, preferDiscrete, diagnostics, null);
    }

    /**
     * Creates with an optional device-name preference for repeatable multi-GPU testing.
     * @param preferredDeviceName name keyword, null/empty for default scoring
     */
    public static VulkanDevice create(String applicationName, boolean preferDiscrete,
                                      Diagnostics diagnostics, String preferredDeviceName) {
        VulkanApi.Probe probe = VulkanApi.probe(applicationName, preferDiscrete, preferredDeviceName);
        if (probe == null) {
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            List<VulkanApi.QueueFamilyInfo> families = VulkanApi.queueFamilies(probe.physicalDevice());
            if (families.isEmpty()) {
                VulkanApi.destroyInstance(probe.instance());
                return null;
            }

            // Request one queue from every available family so later family selection cannot request an uncreated queue.
            FloatBuffer priorities = stack.floats(1.0f);
            VkDeviceQueueCreateInfo.Buffer queueInfos =
                    VkDeviceQueueCreateInfo.calloc(families.size(), stack);
            for (int i = 0; i < families.size(); i++) {
                VulkanApi.QueueFamilyInfo family = families.get(i);
                // LWJGL derives queueCount from the priorities buffer length; one priority requests one queue per family.
                queueInfos.get(i)
                        .sType$Default()
                        .queueFamilyIndex(family.index())
                        .pQueuePriorities(stack.floats(1.0f));
            }

            if (diagnostics != null && diagnostics.isVerbose()) {
                diagnostics.debug(tr("Requesting queue family: ") + families);
            }

            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pQueueCreateInfos(queueInfos);

            // Enable supported optional device extensions, excluding swapchain on this offscreen device. Swapchain requires instance surface extensions absent from the minimal probe instance, and this device creates no surface. A future windowed path must enable both instance and device prerequisites together. Enumerated support remains distinct from enabled features.
            List<String> wanted = WANTED_DEVICE_EXTENSIONS;
            List<String> enabled = new ArrayList<>();
            java.util.Set<String> available = probe.capabilities().extensions();
            for (String name : wanted) {
                if (available.contains(name)) {
                    enabled.add(name);
                }
            }
            if (!enabled.isEmpty()) {
                PointerBuffer names = stack.mallocPointer(enabled.size());
                for (String name : enabled) {
                    names.put(stack.UTF8(name));
                }
                names.flip();
                deviceInfo.ppEnabledExtensionNames(names);
            }

            // Extension names alone do not enable device features. Offscreen
            // regression devices need the same explicit feature negotiation as hosts.
            var features = org.lwjgl.vulkan.VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            var features12 = org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            var features13 = org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
            if (probe.capabilities().apiVersion() >= org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2)
                features.pNext(features12);
            if (probe.capabilities().apiVersion() >= org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3)
                features.pNext(features13);
            if (enabled.contains("VK_KHR_acceleration_structure"))
                features.pNext(org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default());
            if (enabled.contains("VK_KHR_ray_query"))
                features.pNext(org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default());
            if (enabled.contains("VK_KHR_ray_tracing_pipeline"))
                features.pNext(org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default());
            if (enabled.contains("VK_EXT_mutable_descriptor_type"))
                features.pNext(org.lwjgl.vulkan.VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT.calloc(stack).sType$Default());
            org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2(probe.physicalDevice(), features);
            deviceInfo.pNext(features);
            PointerBuffer pDevice = stack.mallocPointer(1);
            int err = vkCreateDevice(probe.physicalDevice(), deviceInfo, null, pDevice);
            if (err != VK_SUCCESS) {
                // Retry device creation once with conservative extensions if optional features/extensions make creation fail, preserving a usable fallback device.
                if (diagnostics != null) {
                    diagnostics.warn(tr("vkCreateDevice failed (") + resultName(err) + "），"
                            + tr("Retrying with minimal extensions; requested: ") + enabled);
                }
                List<String> minimal = new ArrayList<>();
                for (String name : List.of("VK_KHR_synchronization2",
                        "VK_KHR_timeline_semaphore")) {
                    if (available.contains(name)) {
                        minimal.add(name);
                    }
                }
                VkDeviceCreateInfo retryInfo = VkDeviceCreateInfo.calloc(stack)
                        .sType$Default()
                        .pQueueCreateInfos(queueInfos);
                if (!minimal.isEmpty()) {
                    PointerBuffer names = stack.mallocPointer(minimal.size());
                    for (String name : minimal) {
                        names.put(stack.UTF8(name));
                    }
                    names.flip();
                    retryInfo.ppEnabledExtensionNames(names);
                }
                PointerBuffer pRetry = stack.mallocPointer(1);
                int retryErr = vkCreateDevice(probe.physicalDevice(), retryInfo, null, pRetry);
                if (retryErr != VK_SUCCESS) {
                    if (diagnostics != null) {
                        diagnostics.error(tr("vkCreateDevice retry failed: ") + resultName(retryErr)
                                + tr(" (minimal extensions: ") + minimal + "）");
                    }
                    VulkanApi.destroyInstance(probe.instance());
                    return null;
                }
                VkDevice retryDevice = new VkDevice(pRetry.get(0), probe.physicalDevice(), retryInfo);
                if (diagnostics != null) {
                    diagnostics.warn(tr("Device created with reduced extensions (") + minimal
                            + tr("); optional features such as ray tracing may be unavailable"));
                }
                return new VulkanDevice(retryDevice, probe.physicalDevice(), probe.capabilities(),
                        diagnostics, true, probe.instance().address());
            }
            VkDevice device = new VkDevice(pDevice.get(0), probe.physicalDevice(), deviceInfo);
            if (diagnostics != null) {
                diagnostics.info(tr("Created Vulkan device: ") + probe.capabilities().deviceName()
                        + " API " + probe.capabilities().apiVersionString()
                        + tr(" VRAM ") + probe.capabilities().deviceMemoryGiB()
                        + tr(" queue families=") + families.size());
            }
            return new VulkanDevice(device, probe.physicalDevice(), probe.capabilities(),
                    diagnostics, true, probe.instance().address());
        } catch (Throwable t) {
            if (diagnostics != null) {
                diagnostics.error(tr("Device creation threw"), t);
            }
            return null;
        }
    }

    /**
     * Adopts an external device without ownership of either device or instance; the host remains
     * responsible for destruction.
     */
    public static VulkanDevice adopt(org.lwjgl.vulkan.VkInstance instance,
                                     long vkDeviceHandle, long vkPhysicalDeviceHandle,
                                     GpuCapabilities capabilities, Diagnostics diagnostics) {
        Objects.requireNonNull(capabilities, "capabilities");
        // Validate parameters before LWJGL construction to retain actionable failure causes.
        if (vkDeviceHandle == 0L) {
            if (diagnostics != null) {
                diagnostics.error(tr("Cannot attach external device: VkDevice handle is zero"));
            }
            return null;
        }
        if (vkPhysicalDeviceHandle == 0L) {
            if (diagnostics != null) {
                diagnostics.error(tr("Cannot attach external device: VkPhysicalDevice handle missing. ")
                        + tr("The handle wrapper requires it; enumerate it first when VkInstance is available ")
                        + tr("(see RenderDriverImpl#resolveHostDevice)."));
            }
            return null;
        }
        // VkPhysicalDevice needs a nonnull VkInstance to obtain its function capabilities, even when wrapping an existing handle.
        if (instance == null) {
            if (diagnostics != null) {
                diagnostics.error(tr("Cannot attach external device: VkInstance missing. ")
                        + tr("LWJGL wrappers require its capabilities for function dispatch. ")
                        + tr("Pass the actual host instance that owns the device, never a newly created substitute."));
            }
            return null;
        }
        try {
            // Wrap the borrowed VkDevice with a nonnull create-info containing only host-enabled extensions. LWJGL derives its function table from that list; physical support alone cannot enable functions or capabilities.
            org.lwjgl.vulkan.VkPhysicalDevice physical =
                    new org.lwjgl.vulkan.VkPhysicalDevice(vkPhysicalDeviceHandle, instance);
            org.lwjgl.vulkan.VkDeviceCreateInfo createInfo =
                    org.lwjgl.vulkan.VkDeviceCreateInfo.calloc();
            org.lwjgl.PointerBuffer extensionNames = null;
            try {
                java.util.List<String> extensions = new java.util.ArrayList<>(capabilities.extensions());
                if (!extensions.isEmpty()) {
                    extensionNames = org.lwjgl.system.MemoryUtil.memAllocPointer(extensions.size());
                    for (String extension : extensions) {
                        extensionNames.put(org.lwjgl.system.MemoryUtil.memUTF8(extension));
                    }
                    extensionNames.flip();
                    createInfo.sType$Default().ppEnabledExtensionNames(extensionNames);
                } else {
                    createInfo.sType$Default();
                }
                VkDevice device = new VkDevice(vkDeviceHandle, physical, createInfo);
                return new VulkanDevice(device, physical, capabilities, diagnostics, false, instance.address());
            } finally {
                createInfo.free();
                if (extensionNames != null) {
                    org.lwjgl.system.MemoryUtil.memFree(extensionNames);
                }
            }
        } catch (Throwable t) {
            if (diagnostics != null) {
                diagnostics.error(tr("Failed to attach external device"), t);
            }
            return null;
        }
    }

    private VkQueue retrieveQueue(int family, int index) {
        return queues.computeIfAbsent(family * 100 + index, k -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pQueue = stack.mallocPointer(1);
                vkGetDeviceQueue(vkDevice, family, index, pQueue);
                long handle = pQueue.get(0);
                if (handle == 0L) {
                    // A zero queue handle means its family was not requested at device creation; report the family immediately.
                    throw new dev.luxloader.core.util.LuxException(
                            tr("vkGetDeviceQueue returned a null handle (family=") + family + ", index=" + index
                                    + tr("). This queue family may not have been requested at vkCreateDevice."));
                }
                return new VkQueue(handle, vkDevice);
            }
        });
    }

    // ---------------- GpuDevice ----------------

    @Override
    public GpuCapabilities capabilities() {
        return capabilities;
    }

    /**
     * Installs an issue reporter after device creation/attachment.
     * @param reporter receives stable issue key and readable details; null disables reporting
     */
    public void setIssueReporter(java.util.function.BiConsumer<String, String> reporter) {
        this.issueReporter = reporter;
    }

    /**
     * Reports a failed native path if an issue sink exists. Missing diagnostic infrastructure must not add
     * another failure.
     */
    public void reportIssue(String key, String note) {
        java.util.function.BiConsumer<String, String> reporter = issueReporter;
        if (reporter == null) {
            return;
        }
        try {
            reporter.accept(key, note);
        } catch (RuntimeException ignored) {
            // Issue reporting must not hide the original native error.
        }
    }

    @Override
    public long nativeHandle() {
        return vkDevice.address();
    }

    @Override
    public GpuCommands commands() {
        return commands;
    }

    @Override
    public GpuQueue requestQueue(String hint) {
        String h = hint == null ? "" : hint.toLowerCase(java.util.Locale.ROOT);
        if (h.contains("compute") || h.contains("async") || h.contains("ssao") || h.contains("fg")) {
            return computeQueue;
        }
        if (h.contains("transfer") || h.contains("upload")) {
            return transferQueue;
        }
        return graphicsQueue;
    }

    @Override
    public ImageHandle createImage(ImageDesc desc) {
        VulkanResourceProvider provider = new VulkanResourceProvider(this, "adhoc", 0L);
        return provider.image(desc);
    }
    @Override
    public Handle createBuffer(dev.luxloader.api.gpu.BufferDesc desc) {
        VulkanResourceProvider provider = new VulkanResourceProvider(this, "adhoc", 0L);
        return provider.buffer(desc);
    }

    @Override
    public ImageHandle importImage(ImageHandle external, ImageDesc desc) {
        throw new UnsupportedOperationException(
                tr("Device-level importImage is not implemented; use a pipeline-scoped GpuResourceProvider ")
                        + tr("or an adapter-provided external memory implementation"));
    }

    @Override
    public ImageHandle exportImage(ImageHandle image) {
        return ImageHandle.win32(0L, image == null ? "null" : image.label());
    }

    /**
     * Shared device-level provider for unscoped resource creation. Acceleration structures must retain the
     * same provider across allocation/build/release because it owns their backing storage records.
     */
    private VulkanResourceProvider adhocProvider;

    /** Device-scoped resources, including acceleration-structure storage. */
    public VulkanResourceProvider deviceScopedResources() {
        if (adhocProvider == null) {
            adhocProvider = new VulkanResourceProvider(this, "adhoc", 0L);
        }
        return adhocProvider;
    }

    @Override
    public dev.luxloader.api.gpu.AcceleratorHandle createAccelerator(
            dev.luxloader.api.gpu.AccelStructDesc desc) {
        return deviceScopedResources().accelerator(desc);
    }

    /**
     * Device extensions this loader asks for when it owns the device, in order.
     *
     * <p>Only enable what is actually used, and only what can legally be enabled. Both halves
     * matter: {@code VK_KHR_swapchain} was removed from this list because it (a) is never used
     * here -- the loader's own device creates no swapchain and has no {@code VkSurfaceKHR},
     * the host owns presentation -- and (b) requires the INSTANCE extension
     * {@code VK_KHR_surface}, which {@link VulkanApi}'s minimal probe instance does not enable,
     * so requesting it produced
     * {@code VUID-vkCreateDevice-ppEnabledExtensionNames-01387} on every device this loader
     * created itself.
     *
     * <p>{@code VulkanDeviceExtensionDependencyTest} validates this list against the
     * authoritative dependency graph in the SDK's {@code vk.xml}, so adding an extension with
     * an unmet dependency fails the build instead of shipping.
     */
    static final List<String> WANTED_DEVICE_EXTENSIONS = List.of(
            "VK_EXT_mutable_descriptor_type",
            "VK_KHR_get_memory_requirements2",
            "VK_KHR_dedicated_allocation",
            "VK_KHR_shader_float16_int8",
            "VK_EXT_subgroup_size_control",
            "VK_KHR_synchronization2",
            "VK_EXT_descriptor_indexing",
            "VK_KHR_timeline_semaphore",
            "VK_KHR_buffer_device_address",
            "VK_EXT_descriptor_buffer",
            "VK_KHR_acceleration_structure",
            "VK_KHR_ray_query",
            "VK_KHR_ray_tracing_pipeline",
            "VK_KHR_deferred_host_operations");

    /** Guards close(): the host hook and the loader close path both call it. */
    private boolean closed;

    /**
     * Wait for the device to go idle without destroying anything.
     *
     * <p>Needed before releasing our objects while attached to the host device:
     * the host is about to tear down its allocator and the device itself.
     */
    /**
     * Drop the remembered layout of an image that is being destroyed.
     *
     * <p>The layout table lives in VulkanCommands and is device-wide now. A destroyed
     * VkImage handle can be reused by the driver, and a stale entry would then make a
     * brand-new image inherit a confident but wrong layout claim.
     */
    public void forgetImage(long vkImage) {
        commands.forgetImage(vkImage);
    }

    public void waitIdleAll() {
        if (!closed) {
            vkDeviceWaitIdle(vkDevice);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Complete owned submissions before destroying command resources or mapped uploads.
        if (owned) vkDeviceWaitIdle(vkDevice);
        // Unconditional: the child objects are ours, the host device is not.
        commands.close();
        if (owned) {
            vkDestroyDevice(vkDevice, null);
        }
        memoryProperties.free();
    }

    // Internal helpers for commands/providers.

    public VkDevice vkDevice() {
        return vkDevice;
    }

    public org.lwjgl.vulkan.VkPhysicalDevice physicalDevice() {
        return physicalDevice;
    }

    public VkPhysicalDeviceMemoryProperties memoryProperties() {
        return memoryProperties;
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }

    public GpuQueue graphicsQueue() {
        return graphicsQueue;
    }

    public GpuQueue computeQueue() {
        return computeQueue;
    }

    public GpuQueue transferQueue() {
        return transferQueue;
    }

    /** True for owned devices, false for adopted devices. */
    public boolean isOwned() {
        return owned;
    }

    /** Records resource creation for memory accounting and diagnostics. */
    void onResourceCreated(String kind, String label, long totalBytes) {
        createdResources++;
        resourceBytes = totalBytes;
        if (diagnostics != null && diagnostics.isVerbose()) {
            diagnostics.debug(tr("Resource +1 ") + kind + " " + label + tr(" (total ") + (totalBytes >> 20) + " MiB）");
        }
    }

    void onResourceDestroyed(long bytes) {
        resourceBytes = Math.max(0, resourceBytes - bytes);
    }

    long trackedResourceBytes() {
        return resourceBytes;
    }

    long createdResourceCount() {
        return createdResources;
    }

    /** Nonblocking staging upload. */
    void uploadBuffer(long bufferHandle, long offset, ByteBuffer data) {
        commands.uploadToBuffer(bufferHandle, offset, data);
    }

    /** Queues asynchronous readback for the next frame boundary. */
    void queueReadback(long bufferHandle, long offset, long size, Consumer<ByteBuffer> callback) {
        synchronized (pendingReadbacks) {
            pendingReadbacks.add(() -> {
                ByteBuffer result = commands.readBufferBlocking(bufferHandle, offset, size);
                try {
                    callback.accept(result);
                } finally {
                    MemoryUtil.memFree(result);
                }
            });
        }
    }

    /** Processes queued readbacks at a frame boundary. */
    public void flushReadbacks() {
        List<Runnable> batch;
        synchronized (pendingReadbacks) {
            batch = new ArrayList<>(pendingReadbacks);
            pendingReadbacks.clear();
        }
        for (Runnable r : batch) {
            try {
                r.run();
            } catch (RuntimeException e) {
                if (diagnostics != null) {
                    diagnostics.error(tr("Asynchronous readback failed"), e);
                }
            }
        }
    }

    /** Creates a compute pipeline. */
    public long createComputePipeline(ComputePipelineDesc desc, byte[] spirv) {
        return commands.createComputePipeline(desc, spirv);
    }

    @Override
    public long createGraphicsPipeline(dev.luxloader.api.gpu.GraphicsPipelineDesc desc,
                                       byte[] vertexSpirv, byte[] fragmentSpirv) {
        return commands.createGraphicsPipeline(desc, vertexSpirv, fragmentSpirv);
    }

    /** Creates a shader module. */
    public long createShaderModule(byte[] spirv) {
        return commands.createShaderModule(spirv);
    }

    /** Waits for device idle. */
    public void waitIdle() {
        vkDeviceWaitIdle(vkDevice);
    }

    /**
     * Blocking buffer readback for diagnostics/tests only. Per-frame rendering must use readbackAsync to
     * avoid CPU/GPU stalls.
     */
    public java.nio.ByteBuffer readBufferBlocking(long bufferHandle, long offset, long size) {
        return commands.readBufferBlocking(bufferHandle, offset, size);
    }

    /** Formats VkResult for diagnostics. */
    public static String resultName(int result) {
        return switch (result) {
            case VK_SUCCESS -> "VK_SUCCESS";
            case VK_NOT_READY -> "VK_NOT_READY";
            case VK_TIMEOUT -> "VK_TIMEOUT";
            case VK_INCOMPLETE -> "VK_INCOMPLETE";
            case VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            case VK_ERROR_MEMORY_MAP_FAILED -> "VK_ERROR_MEMORY_MAP_FAILED";
            case VK_ERROR_LAYER_NOT_PRESENT -> "VK_ERROR_LAYER_NOT_PRESENT";
            case VK_ERROR_EXTENSION_NOT_PRESENT -> "VK_ERROR_EXTENSION_NOT_PRESENT";
            case VK_ERROR_FEATURE_NOT_PRESENT -> "VK_ERROR_FEATURE_NOT_PRESENT";
            case VK_ERROR_INCOMPATIBLE_DRIVER -> "VK_ERROR_INCOMPATIBLE_DRIVER";
            case VK_ERROR_FORMAT_NOT_SUPPORTED -> "VK_ERROR_FORMAT_NOT_SUPPORTED";
            case VK_ERROR_UNKNOWN -> "VK_ERROR_UNKNOWN";
            default -> "VkResult(" + result + ")";
        };
    }

    /** Actual parent instance handle, including when borrowed from the host. */
    public long nativeInstanceHandle() {
        return nativeInstanceHandle;
    }
}
