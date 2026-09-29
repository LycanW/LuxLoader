package dev.luxloader.api.pipeline;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;
import java.util.Optional;

/**
 * Presentation target and request. The loader supplies a Target; the pipeline returns which image to
 * present, when to present it and whether to insert generated frames.
 * @param target current swapchain target
 * @param request requested presentation action
 */
public record PresentRequest(Target target, Request request) {

    /**
     * Read-only swapchain target description.
     * @param image currently writable swapchain image
     * @param width width
     * @param height height
     * @param format format
     * @param imageIndex swapchain image index
     * @param presentMode current presentation mode
     * @param hdr whether HDR is active
     * @param vsync whether VSync is active
     */
    public record Target(
            dev.luxloader.api.gpu.ImageHandle image,
            int width,
            int height,
            dev.luxloader.api.gpu.GpuFormat format,
            int imageIndex,
            PresentMode presentMode,
            boolean hdr,
            boolean vsync) {

        public Target {
            Objects.requireNonNull(format, "format");
            presentMode = presentMode == null ? PresentMode.FIFO : presentMode;
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException(tr("Swapchain dimensions must be positive"));
            }
        }

        public boolean hasImage() {
            return image != null && !image.isNull();
        }

        public float aspectRatio() {
            return (float) width / height;
        }
    }

    /** Presentation mode matching VkPresentModeKHR semantics. */
    public enum PresentMode {
        /** FIFO vertical synchronization with at least one queued frame. */
        FIFO,
        /** Relaxed FIFO: synchronized presentation with immediate late frames that may tear. */
        FIFO_RELAXED,
        /** Immediate presentation without queueing; low latency with possible tearing. */
        IMMEDIATE,
        /** Mailbox presentation: triple buffering without tearing and lower latency than FIFO. */
        MAILBOX,
        /** Pipeline-controlled presentation timing, usually required for frame generation. */
        PIPELINE_CONTROLLED
    }

    /**
     * Pipeline presentation request.
     * @param image image to present; null uses the current swapchain image
     * @param present whether the loader handles presentation; false means the pipeline submits elsewhere
     * @param additionalFrames generated display frames in presentation order
     * @param targetIntervalNs desired subsequent frame interval; zero delegates to display refresh timing
     * @param pacing whether the loader takes over pacing, usually false without frame generation
     * @param note diagnostic note
     */
    public record Request(
            dev.luxloader.api.gpu.ImageHandle image,
            boolean present,
            java.util.List<dev.luxloader.api.gpu.ImageHandle> additionalFrames,
            long targetIntervalNs,
            boolean pacing,
            String note) {

        /** Default request: present the current swapchain content without additional frames. */
        public static final Request DEFAULT = new Request(null, true, java.util.List.of(), 0L, false, "");

        public Request {
            additionalFrames = additionalFrames == null ? java.util.List.of() : java.util.List.copyOf(additionalFrames);
            note = note == null ? "" : note;
            if (additionalFrames.size() > 8) {
                throw new IllegalArgumentException(tr("Too many generated frames inserted in one frame: ") + additionalFrames.size());
            }
        }

        /** Ask the loader to present a supplied image. */
        public static Request of(dev.luxloader.api.gpu.ImageHandle image) {
            return new Request(image, true, java.util.List.of(), 0L, false, "");
        }

        /**
         * Presents interpolated frames after the current real frame.
         * @param base real rendered frame
         * @param generated interpolated frames in time order
         * @param intervalNs interval between generated frames from the display refresh rate
         */
        public static Request frameGeneration(dev.luxloader.api.gpu.ImageHandle base,
                                             java.util.List<dev.luxloader.api.gpu.ImageHandle> generated,
                                             long intervalNs) {
            return new Request(base, true, generated, intervalNs, true, "frame-generation");
        }

        /** Skip presentation, for example to discard a frame and avoid pacing jitter. */
        public static Request skip(String why) {
            return new Request(null, false, java.util.List.of(), 0L, false, why == null ? "" : why);
        }

        /** Total frames to present: one plus additional frames. */
        public int totalFrames() {
            return present ? 1 + additionalFrames.size() : 0;
        }

        public boolean generatesFrames() {
            return !additionalFrames.isEmpty();
        }

        public Optional<dev.luxloader.api.gpu.ImageHandle> primary() {
            return Optional.ofNullable(image);
        }
    }
}
