package dev.luxloader.api.gpu;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable physical device capabilities captured during initialization. Capability negotiation,
 * upscale backend selection, pipeline eligibility and diagnostics use this snapshot.
 * @param deviceName device name
 * @param vendor GPU vendor
 * @param vendorId raw vendor ID
 * @param deviceId raw device ID
 * @param driverVersion raw driver version
 * @param driverName driver name, if {@code VK_KHR_driver_properties} is exposed
 * @param apiVersion Vulkan version encoded as {@code major<<22 | minor<<12 | patch}
 * @param extensions available device extensions
 * @param instanceExtensions available instance extensions
 * @param maxComputeWorkGroupInvocations maximum invocations per workgroup
 * @param maxComputeSharedMemorySize maximum shared memory bytes per workgroup
 * @param timestampPeriod timestamp period in nanoseconds
 * @param deviceMemoryBytes device-local memory bytes
 * @param maxImageDimension2D maximum 2D image dimension
 */
public record GpuCapabilities(
        String deviceName,
        GpuVendor vendor,
        int vendorId,
        int deviceId,
        int driverVersion,
        String driverName,
        int apiVersion,
        java.util.Set<String> extensions,
        java.util.Set<String> instanceExtensions,
        int maxComputeWorkGroupInvocations,
        int maxComputeSharedMemorySize,
        float timestampPeriod,
        long deviceMemoryBytes,
        int maxImageDimension2D) {

    public GpuCapabilities {
        Objects.requireNonNull(deviceName, "deviceName");
        Objects.requireNonNull(vendor, "vendor");
        driverName = driverName == null ? "" : driverName;
        extensions = extensions == null ? java.util.Set.of() : java.util.Set.copyOf(extensions);
        instanceExtensions = instanceExtensions == null ? java.util.Set.of() : java.util.Set.copyOf(instanceExtensions);
    }

    /**
     * Whether the device supports an extension: either the driver advertises it or it was promoted to a
     * core version no greater than the device API version. Drivers need not advertise promoted names;
     * checking advertisements alone incorrectly rejects core functionality such as {@code
     * VK_KHR_get_memory_requirements2}. See {@link PromotedExtensions}.
     */
    public boolean supports(String extension) {
        return extensions.contains(extension)
                || PromotedExtensions.deviceAvailableAtCore(extension, apiVersion);
    }

    /** Whether an instance extension is supported, including core promotion as in supports(). */
    public boolean supportsInstance(String extension) {
        return instanceExtensions.contains(extension)
                || PromotedExtensions.instanceAvailableAtCore(extension, apiVersion);
    }

    /** Whether all requested device extensions are supported. */
    public boolean supportsAll(String... required) {
        for (String e : required) {
            if (!supports(e)) {
                return false;
            }
        }
        return true;
    }

    /** Whether any requested extension is supported, for alternative extension sets. */
    public boolean supportsAny(String... candidates) {
        for (String c : candidates) {
            if (supports(c)) {
                return true;
            }
        }
        return false;
    }

    /** Vulkan major version. */
    public int apiMajor() {
        return apiVersion >>> 22;
    }

    /** Vulkan minor version. */
    public int apiMinor() {
        return (apiVersion >>> 12) & 0x3FF;
    }

    /** Vulkan patch version. */
    public int apiPatch() {
        return apiVersion & 0xFFF;
    }

    /** Version string such as 1.3.280. */
    public String apiVersionString() {
        return apiMajor() + "." + apiMinor() + "." + apiPatch();
    }

    /** Whether the API version is at least major.minor. */
    public boolean apiAtLeast(int major, int minor) {
        return apiMajor() > major || (apiMajor() == major && apiMinor() >= minor);
    }

    /** VRAM in GiB with one decimal place for diagnostics. */
    public String deviceMemoryGiB() {
        return String.format(java.util.Locale.ROOT, "%.1f GiB", deviceMemoryBytes / (1024.0 * 1024 * 1024));
    }

    /** Whether hardware ray tracing is available through a supported tracing path. */
    public boolean supportsRayTracing() {
        return supportsAll("VK_KHR_acceleration_structure", "VK_KHR_ray_query")
                || supportsAll("VK_KHR_acceleration_structure", "VK_KHR_ray_tracing_pipeline");
    }

    /** Descriptor buffer support for reducing CPU descriptor overhead. */
    public boolean supportsDescriptorBuffer() {
        return supports("VK_EXT_descriptor_buffer");
    }

    /** Timestamp query support for GPU timing. */
    public boolean supportsTimestamps() {
        return timestampPeriod > 0f;
    }

    /** Enumerate capabilities for diagnostic reports. */
    public void forEach(java.util.function.BiConsumer<String, String> consumer) {
        consumer.accept("device", deviceName);
        consumer.accept("vendor", vendor.displayName() + " (0x" + Integer.toHexString(vendorId) + ")");
        consumer.accept("deviceId", "0x" + Integer.toHexString(deviceId));
        consumer.accept("driver", driverName.isEmpty() ? ("version " + driverVersion) : driverName);
        consumer.accept("vulkan", apiVersionString());
        consumer.accept("vram", deviceMemoryGiB());
        consumer.accept("maxComputeInvocations", Integer.toString(maxComputeWorkGroupInvocations));
        consumer.accept("maxComputeSharedMemory", maxComputeSharedMemorySize + " B");
        consumer.accept("extensions", Integer.toString(extensions.size()));
    }

    /** Format extensions one per line. */
    public void forEachExtension(Consumer<String> consumer) {
        extensions.stream().sorted().forEach(consumer);
    }
}
