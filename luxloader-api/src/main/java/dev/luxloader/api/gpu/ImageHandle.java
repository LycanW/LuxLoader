package dev.luxloader.api.gpu;

import java.util.Objects;

/**
 * Opaque GPU resource handle. The API avoids native Vulkan types to support different device owners,
 * native bridges and future backends without depending on LWJGL. The provider defines handle
 * semantics. Native access uses {@link GpuContext#unwrap(String, long)} or an implementation-specific
 * cast.
 * @param kind resource kind
 * @param bits provider-defined handle bits or driver ID
 * @param label debug name for validation and diagnostics
 */
public record ImageHandle(Kind kind, long bits, String label) {

    /** Handle semantics. */
    public enum Kind {
        /** Vulkan {@code VkImage}。 */
        VK_IMAGE,
        /** Vulkan {@code VkImageView}。 */
        VK_IMAGE_VIEW,
        /** Vulkan {@code VkBuffer}。 */
        VK_BUFFER,
        /** Windows shared HANDLE exported through VK_KHR_external_memory_win32 for device/process interoperability. */
        WIN32_HANDLE,
        /** Other handle interpreted by its provider. */
        OPAQUE
    }

    public ImageHandle {
        Objects.requireNonNull(kind, "kind");
        label = label == null ? "" : label;
    }

    public static ImageHandle of(Kind kind, long bits, String label) {
        return new ImageHandle(kind, bits, label);
    }

    public static ImageHandle vkImage(long image, String label) {
        return new ImageHandle(Kind.VK_IMAGE, image, label);
    }

    public static ImageHandle win32(long handle, String label) {
        return new ImageHandle(Kind.WIN32_HANDLE, handle, label);
    }

    public boolean isNull() {
        return bits == 0L;
    }
}
