package dev.luxloader.api.gpu;

/**
 * Ray tracing acceleration structure handle. Unsupported devices return unsupported() so pipelines can
 * choose a fallback. nativeHandle is a VkAccelerationStructureKHR (zero when unavailable);
 * deviceAddress allows shader access, size is bytes including any compaction, label aids debugging,
 * and supported reports actual availability.
 */
public record AcceleratorHandle(
        AccelStructDesc.Type kind,
        long nativeHandle,
        long deviceAddress,
        long size,
        String label,
        boolean supported) {

    public AcceleratorHandle {
        label = label == null ? "" : label;
    }

    /** Placeholder handle for devices without ray tracing support. */
    public static AcceleratorHandle unsupported(String label) {
        return new AcceleratorHandle(AccelStructDesc.Type.BOTTOM_LEVEL, 0L, 0L, 0L, label, false);
    }

    public static AcceleratorHandle of(AccelStructDesc.Type kind, long nativeHandle, long deviceAddress,
                                       long size, String label) {
        return new AcceleratorHandle(kind, nativeHandle, deviceAddress, size, label, nativeHandle != 0L);
    }

    public boolean isTopLevel() {
        return kind == AccelStructDesc.Type.TOP_LEVEL;
    }

    /** Whether shaders can reference this structure by device address. */
    public boolean hasDeviceAddress() {
        return supported && deviceAddress != 0L;
    }
}
