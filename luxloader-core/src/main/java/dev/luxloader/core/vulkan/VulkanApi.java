package dev.luxloader.core.vulkan;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuVendor;

import java.nio.IntBuffer;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.VK14.VK_API_VERSION_1_4;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

/**
 * Vulkan bootstrap for instance creation, device enumeration and capability capture, retaining
 * configurable function-provider access. Vulkan version alone does not imply ray tracing or
 * descriptor-buffer support; probe the required extensions explicitly.
 */
public final class VulkanApi {

    private static volatile Boolean available;
    private static volatile String unavailableReason = "";

    private VulkanApi() {
    }

    /** Checks JVM Vulkan availability and preserves the failure reason for diagnostics. */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (VulkanApi.class) {
            if (available == null) {
                try {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        IntBuffer version = stack.mallocInt(1);
                        int err = vkEnumerateInstanceVersion(version);
                        available = err == VK_SUCCESS && version.get(0) > 0;
                        if (!available) {
                            unavailableReason = tr("vkEnumerateInstanceVersion returned err=") + err;
                        }
                    }
                } catch (Throwable t) {
                    // Includes missing Vulkan libraries and class initialization failures.
                    available = false;
                    unavailableReason = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
            }
            return available;
        }
    }

    /** Unavailability reason, empty on success. */
    public static String unavailableReason() {
        isAvailable();
        return unavailableReason;
    }

    /** Loader-supported Vulkan version queried without creating an instance. */
    public static int loaderInstanceVersion() {
        if (!isAvailable()) {
            return 0;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer version = stack.mallocInt(1);
            int err = vkEnumerateInstanceVersion(version);
            return err == VK_SUCCESS ? version.get(0) : VK_API_VERSION_1_0;
        } catch (Throwable t) {
            return VK_API_VERSION_1_0;
        }
    }

    /** Formats a Vulkan version. */
    public static String formatVersion(int apiVersion) {
        return (apiVersion >>> 22) + "." + ((apiVersion >>> 12) & 0x3FF) + "." + (apiVersion & 0xFFF);
    }

    /** Supported version ceiling for diagnostics. */
    public static int maxKnownVersion() {
        return VK_API_VERSION_1_4;
    }

    /**
     * Probe result.
     * @param instance instance, owned only when ownsInstance is true
     * @param physicalDevice selected physical device
     * @param capabilities capability snapshot
     * @param deviceIndex selected index
     * @param deviceCount total devices
     * @param ownsInstance false for borrowed host instances, which must not be destroyed here
     */
    public record Probe(VkInstance instance, VkPhysicalDevice physicalDevice,
                        GpuCapabilities capabilities, int deviceIndex, int deviceCount, boolean ownsInstance) {
        public Probe(VkInstance instance, VkPhysicalDevice physicalDevice,
                     GpuCapabilities capabilities, int deviceIndex, int deviceCount) {
            this(instance, physicalDevice, capabilities, deviceIndex, deviceCount, true);
        }
    }

    /** Rebuilds LWJGL dispatch tables for the actual host instance; creates no Vulkan objects. */
    public static Probe borrowHostDevice(long instanceHandle, long physicalHandle,
                                         int instanceApiVersion, Set<String> enabledInstanceExtensions) {
        if (instanceHandle == 0 || physicalHandle == 0 || instanceApiVersion == 0)
            throw new IllegalArgumentException("Host must provide its instance, physical device and instance API version");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var app = VkApplicationInfo.calloc(stack).sType$Default().apiVersion(instanceApiVersion);
            var info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            if (!enabledInstanceExtensions.isEmpty()) {
                var names = stack.mallocPointer(enabledInstanceExtensions.size());
                for (String extension : enabledInstanceExtensions) names.put(stack.UTF8(extension));
                info.ppEnabledExtensionNames(names.flip());
            }
            var instance = new VkInstance(instanceHandle, info);
            var count = stack.mallocInt(1);
            int result = vkEnumeratePhysicalDevices(instance, count, null);
            if (result != VK_SUCCESS || count.get(0) == 0)
                throw new IllegalStateException("Cannot enumerate host physical devices: " + result);
            var handles = stack.mallocPointer(count.get(0));
            result = vkEnumeratePhysicalDevices(instance, count, handles);
            if (result != VK_SUCCESS) throw new IllegalStateException("Host device enumeration failed: " + result);
            for (int index = 0; index < count.get(0); index++) {
                if (handles.get(index) != physicalHandle) continue;
                var physical = new VkPhysicalDevice(physicalHandle, instance);
                return new Probe(instance, physical, readCapabilities(instance, physical), index, count.get(0), false);
            }
            throw new IllegalArgumentException("Physical device does not belong to the supplied host instance");
        }
    }

    /**
     * Creates a windowless instance and selects a physical device for early capability discovery.
     * @param applicationName driver-visible application name
     * @param preferDiscrete prefer discrete GPUs
     * @return null if no device is available
     */
    public static Probe probe(String applicationName, boolean preferDiscrete) {
        return probe(applicationName, preferDiscrete, null);
    }

    /**
     * Creates an owned instance for standalone discovery with an optional preferred device name. Host
     * attachment must use borrowHostDevice: never substitute physical-device handles from another instance
     * into the host chain.
     * @param preferredDeviceName desired name, or empty for scoring
     */
    public static Probe probe(String applicationName, boolean preferDiscrete,
                              String preferredDeviceName) {
        if (!isAvailable()) {
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8(applicationName))
                    .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
                    .pEngineName(stack.UTF8("LuxLoader"))
                    .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(Math.min(loaderInstanceVersion(), maxKnownVersion()));

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(appInfo);

            PointerBuffer pInstance = stack.mallocPointer(1);
            int err = vkCreateInstance(createInfo, null, pInstance);
            if (err != VK_SUCCESS) {
                unavailableReason = tr("vkCreateInstance failed: ") + err;
                return null;
            }
            VkInstance instance = new VkInstance(pInstance.get(0), createInfo);

            IntBuffer count = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, count, null);
            int deviceCount = count.get(0);
            if (deviceCount == 0) {
                vkDestroyInstance(instance, null);
                unavailableReason = tr("No Vulkan physical devices found");
                return null;
            }
            PointerBuffer devices = stack.mallocPointer(deviceCount);
            vkEnumeratePhysicalDevices(instance, count, devices);

            int bestIndex = 0;
            long bestScore = Long.MIN_VALUE;
            for (int i = 0; i < deviceCount; i++) {
                VkPhysicalDevice dev = new VkPhysicalDevice(devices.get(i), instance);
                long score = score(dev, preferDiscrete, preferredDeviceName);
                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = i;
                }
            }

            // Reconstruct the chosen device handle and retain its instance.
            VkPhysicalDevice chosen = new VkPhysicalDevice(devices.get(bestIndex), instance);
            GpuCapabilities caps = readCapabilities(instance, chosen);
            return new Probe(instance, chosen, caps, bestIndex, deviceCount);
        } catch (Throwable t) {
            unavailableReason = tr("Probe failed: ") + t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        }
    }

    /**
     * Device scoring prioritizes explicit name match, discrete hardware, memory and API version.
     * Host-selected identity overrides all heuristics.
     */
    private static long score(VkPhysicalDevice device, boolean preferDiscrete,
                              String preferredDeviceName) {        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc();
        try {
            vkGetPhysicalDeviceProperties(device, props);
            long score = 0;
            if (preferredDeviceName != null && !preferredDeviceName.isBlank()) {
                String wanted = preferredDeviceName.trim();
                String actual = props.deviceNameString();
                if (wanted.equalsIgnoreCase(actual)) {
                    // An explicitly selected host device overrides heuristics.
                    score += 1_000_000_000_000L;
                } else if (actual != null && actual.toLowerCase(java.util.Locale.ROOT)
                        .contains(wanted.toLowerCase(java.util.Locale.ROOT))) {
                    // Accept substring name matches below exact matches so users can select familiar vendor/model keywords.
                    score += 900_000_000_000L;
                }
            }
            if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                score += preferDiscrete ? 1_000_000L : 100_000L;
            } else if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) {
                score += 10_000L;
            } else if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU) {
                score -= 50_000L;
            }
            VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.calloc();
            try {
                vkGetPhysicalDeviceMemoryProperties(device, mem);
                long vram = 0;
                for (int i = 0; i < mem.memoryHeapCount(); i++) {
                    if ((mem.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                        vram += mem.memoryHeaps(i).size();
                    }
                }
                score += vram / (1024 * 1024); // Megabyte units.
            } finally {
                mem.free();
            }
            score += (props.apiVersion() >>> 12) * 1L;
            return score;
        } finally {
            props.free();
        }
    }

    /** Captures capabilities. */
    public static GpuCapabilities readCapabilities(VkInstance instance, VkPhysicalDevice device) {
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc();
        VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc();
        VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.calloc();
        try {
            vkGetPhysicalDeviceProperties(device, props);
            vkGetPhysicalDeviceFeatures(device, features);
            vkGetPhysicalDeviceMemoryProperties(device, mem);

            String name = props.deviceNameString();
            int vendorId = props.vendorID();
            int deviceId = props.deviceID();
            int driverVersion = props.driverVersion();
            int apiVersion = props.apiVersion();
            int maxImageDimension2D = props.limits().maxImageDimension2D();
            int maxComputeInvocations = props.limits().maxComputeWorkGroupInvocations();
            int maxComputeShared = props.limits().maxComputeSharedMemorySize();
            float timestampPeriod = props.limits().timestampPeriod();

            long vram = 0;
            for (int i = 0; i < mem.memoryHeapCount(); i++) {
                if ((mem.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    vram += mem.memoryHeaps(i).size();
                }
            }

            Set<String> deviceExts = enumerateDeviceExtensions(device);
            Set<String> instanceExts = enumerateInstanceExtensions();

            return new GpuCapabilities(name, GpuVendor.of(vendorId), vendorId, deviceId, driverVersion,
                    "", apiVersion, deviceExts, instanceExts, maxComputeInvocations, maxComputeShared,
                    timestampPeriod, vram, maxImageDimension2D);
        } finally {
            props.free();
            features.free();
            mem.free();
        }
    }

    /** Enumerates device extensions. */
    public static Set<String> enumerateDeviceExtensions(VkPhysicalDevice device) {
        Set<String> out = new LinkedHashSet<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            int err = vkEnumerateDeviceExtensionProperties(device, (String) null, count, null);
            if (err != VK_SUCCESS || count.get(0) == 0) {
                return out;
            }
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
            vkEnumerateDeviceExtensionProperties(device, (String) null, count, props);
            for (int i = 0; i < count.get(0); i++) {
                out.add(props.get(i).extensionNameString());
            }
        } catch (Throwable t) {
            // Enumeration failure yields an empty extension list without discarding other probe results.
            return out;
        }
        return out;
    }

    /** Enumerates instance extensions. */
    public static Set<String> enumerateInstanceExtensions() {
        Set<String> out = new LinkedHashSet<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            int err = vkEnumerateInstanceExtensionProperties((String) null, count, null);
            if (err != VK_SUCCESS || count.get(0) == 0) {
                return out;
            }
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
            vkEnumerateInstanceExtensionProperties((String) null, count, props);
            for (int i = 0; i < count.get(0); i++) {
                out.add(props.get(i).extensionNameString());
            }
        } catch (Throwable t) {
            return out;
        }
        return out;
    }

    /**
     * Copies queue-family properties into Java values before MemoryStack closes. Returning stack-backed
     * LWJGL structs would expose reclaimed memory.
     * @param index family index
     * @param flags queue flags
     * @param queueCount available queues
     */
    public record QueueFamilyInfo(int index, int flags, int queueCount) {

        public boolean hasGraphics() {
            return (flags & VK_QUEUE_GRAPHICS_BIT) != 0;
        }

        public boolean hasCompute() {
            return (flags & VK_QUEUE_COMPUTE_BIT) != 0;
        }

        public boolean hasTransfer() {
            return (flags & VK_QUEUE_TRANSFER_BIT) != 0;
        }

        /** Compute without graphics: preferred async-compute family. */
        public boolean isDedicatedCompute() {
            return hasCompute() && !hasGraphics();
        }

        /** Transfer-only family for uploads/readbacks without graphics contention. */
        public boolean isDedicatedTransfer() {
            return hasTransfer() && !hasGraphics() && !hasCompute();
        }

        @Override
        public String toString() {
            return "family[" + index + " flags=0x" + Integer.toHexString(flags)
                    + " queues=" + queueCount + (hasGraphics() ? " graphics" : "")
                    + (hasCompute() ? " compute" : "") + (hasTransfer() ? " transfer" : "") + "]";
        }
    }

    /** Returns queue-family value snapshots. Failure yields an empty list; never assume a graphics queue exists. */
    public static java.util.List<QueueFamilyInfo> queueFamilies(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
            int familyCount = count.get(0);
            if (familyCount == 0) {
                return java.util.List.of();
            }
            VkQueueFamilyProperties.Buffer buf = VkQueueFamilyProperties.malloc(familyCount, stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, buf);
            java.util.List<QueueFamilyInfo> out = new java.util.ArrayList<>(familyCount);
            for (int i = 0; i < familyCount; i++) {
                VkQueueFamilyProperties props = buf.get(i);
                // Copy native fields into ordinary values before leaving the stack scope.
                out.add(new QueueFamilyInfo(i, props.queueFlags(), props.queueCount()));
            }
            return java.util.List.copyOf(out);
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    /** Whether a compute family separate from graphics exists. */
    public static boolean hasDedicatedComputeQueue(VkPhysicalDevice device) {
        return queueFamilies(device).stream().anyMatch(QueueFamilyInfo::isDedicatedCompute);
    }

    /** Whether a dedicated transfer family exists. */
    public static boolean hasDedicatedTransferQueue(VkPhysicalDevice device) {
        return queueFamilies(device).stream().anyMatch(QueueFamilyInfo::isDedicatedTransfer);
    }

    /** Destroys the instance. */
    public static void destroyInstance(VkInstance instance) {
        if (instance != null) {
            vkDestroyInstance(instance, null);
        }
    }

    /** Version constants used by requirement validation. */
    public static int apiVersion12() {
        return VK_API_VERSION_1_2;
    }

    public static int apiVersion13() {
        return VK_API_VERSION_1_3;
    }

    public static int apiVersion14() {
        return VK_API_VERSION_1_4;
    }
}
