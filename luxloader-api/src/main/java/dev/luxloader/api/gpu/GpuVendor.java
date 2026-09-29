package dev.luxloader.api.gpu;

/**
 * GPU vendor from Vulkan {@code VkPhysicalDeviceProperties.vendorID}. Used to select upscale/frame
 * generation capabilities: DLSS requires NVIDIA, while XeSS uses Intel XMX or a DP4a fallback on other
 * vendors.
 */
public enum GpuVendor {
    NVIDIA(0x10DE, "NVIDIA", "nvidia"),
    AMD(0x1002, "AMD", "amd"),
    INTEL(0x8086, "Intel", "intel"),
    ARM(0x13B5, "ARM", "arm"),
    QUALCOMM(0x5143, "Qualcomm", "qualcomm"),
    IMAGINATION(0x1010, "Imagination", "imagination"),
    MESA(0x10005, "Mesa", "mesa"),
    MICROSOFT(0x1414, "Microsoft", "microsoft"),
    UNKNOWN(-1, "Unknown", "unknown");

    private final int vendorId;
    private final String displayName;
    private final String key;

    GpuVendor(int vendorId, String displayName, String key) {
        this.vendorId = vendorId;
        this.displayName = displayName;
        this.key = key;
    }

    /** Vulkan vendorID; UNKNOWN maps to -1. */
    public int vendorId() {
        return vendorId;
    }

    public String displayName() {
        return displayName;
    }

    /** Stable lowercase identifier for configuration and diagnostics. */
    public String key() {
        return key;
    }

    public static GpuVendor of(int vendorId) {
        for (GpuVendor v : values()) {
            if (v != UNKNOWN && v.vendorId == vendorId) {
                return v;
            }
        }
        return UNKNOWN;
    }
}
