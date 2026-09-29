package dev.luxloader.api.scene;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.gpu.GpuFormat;
import dev.luxloader.api.gpu.ImageHandle;

import java.util.Objects;

/**
 * Host-owned image with its measured format and dimensions. Opaque handles alone cannot describe safe
 * image views: guessing a host format can cause validation errors or device loss, and sRGB/linear
 * mismatches change brightness.
 * @param name semantic name such as blockAtlas
 * @param handle image handle
 * @param format actual format, or UNDEFINED if unavailable
 * @param width pixel width
 * @param height pixel height
 */
public record SceneImage(String name, ImageHandle handle, GpuFormat format, int width, int height) {

    public SceneImage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handle, "handle");
        format = format == null ? GpuFormat.UNDEFINED : format;
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException(tr("Dimensions must not be negative: ") + width + "x" + height);
        }
    }

    /** Whether a view can be created: valid handle, known format and positive dimensions. */
    public boolean usable() {
        return !handle.isNull() && format != GpuFormat.UNDEFINED && width > 0 && height > 0;
    }

    public String describe() {
        return name + "=" + width + "x" + height + " " + format
                + (usable() ? "" : tr(" (unavailable)"));
    }
}
