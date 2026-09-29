package dev.luxloader.api.gpu;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * Queue handle.
 * @param familyIndex queue family index
 * @param queueIndex index within the family
 * @param dedicated whether the family is separate from graphics
 * @param nativeQueue native {@code VkQueue}, or zero if unavailable
 * @param label usage label
 */
public record GpuQueue(int familyIndex, int queueIndex, boolean dedicated, long nativeQueue, String label) {

    public GpuQueue {
        Objects.requireNonNull(label, "label");
        if (familyIndex < 0 || queueIndex < 0) {
            throw new IllegalArgumentException(tr("Invalid queue index: family=") + familyIndex + " index=" + queueIndex);
        }
    }

    public static GpuQueue of(int familyIndex, int queueIndex, boolean dedicated, long nativeQueue, String label) {
        return new GpuQueue(familyIndex, queueIndex, dedicated, nativeQueue, label);
    }

    /** Short diagnostic description. */
    public String describe() {
        return label + "(family=" + familyIndex + ", index=" + queueIndex
                + (dedicated ? ", dedicated" : ", shared") + ")";
    }
}
