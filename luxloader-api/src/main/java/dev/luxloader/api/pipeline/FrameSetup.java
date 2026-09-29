package dev.luxloader.api.pipeline;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.frame.FrameTiming;
import dev.luxloader.api.gpu.GpuFormat;

/**
 * Per-frame decisions supplied to a pipeline before recording. FrameSetup holds values; {@link
 * dev.luxloader.api.frame.FrameContext} holds resources and the device.
 * @param displayWidth display/swapchain width
 * @param displayHeight display/swapchain height
 * @param renderWidth render width, possibly below display resolution
 * @param renderHeight render height
 * @param renderScale render/display ratio; 1 is native resolution
 * @param colorFormat swapchain color format
 * @param depthFormat game depth format
 * @param hdr whether the swapchain is HDR/10-bit
 * @param sampleCount multisample count
 * @param frameIndex monotonic frame index
 * @param timing CPU/GPU timing, refresh rate and VSync
 * @param resetRequested reset history after resize, dimension change or activation
 * @param uiSeparated whether UI/HUD is rendered separately and can bypass upscaling
 * @param hudHidden whether F1 hides the HUD
 * @param pauseScreenOpen whether the pause menu is open
 * @param worldLoaded whether a world is loaded
 */
public record FrameSetup(
        int displayWidth,
        int displayHeight,
        int renderWidth,
        int renderHeight,
        float renderScale,
        GpuFormat colorFormat,
        GpuFormat depthFormat,
        boolean hdr,
        int sampleCount,
        long frameIndex,
        FrameTiming timing,
        boolean resetRequested,
        boolean uiSeparated,
        boolean hudHidden,
        boolean pauseScreenOpen,
        boolean worldLoaded) {

    public FrameSetup {
        if (displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException(tr("Display dimensions must be positive"));
        }
        if (renderWidth <= 0 || renderHeight <= 0) {
            throw new IllegalArgumentException(tr("Render dimensions must be positive"));
        }
        if (sampleCount < 1) {
            sampleCount = 1;
        }
        colorFormat = colorFormat == null ? GpuFormat.B8G8R8A8_UNORM : colorFormat;
        depthFormat = depthFormat == null ? GpuFormat.D32_SFLOAT : depthFormat;
        // FrameContext receives this timing object. A stale timing.frameIndex
        // freezes temporal sampling even when setup.frameIndex advances.
        timing = timing == null ? FrameTiming.unknown(frameIndex) : timing.withFrameIndex(frameIndex);
        if (renderScale <= 0f) {
            renderScale = (float) renderWidth / displayWidth;
        }
    }

    /** Whether history must be invalidated for a key frame. */
    public boolean isKeyFrame() {
        return resetRequested || frameIndex == 0L;
    }

    /** Whether frame pacing can change: an active world without an open menu. */
    public boolean isGameplayFrame() {
        return worldLoaded && !pauseScreenOpen;
    }

    /** Upscale multiplier. */
    public float upscaleFactor() {
        return (float) displayWidth / renderWidth;
    }

    /** Whether upscaling is active. */
    public boolean isUpscaling() {
        return displayWidth > renderWidth || displayHeight > renderHeight;
    }

    /** Display aspect ratio. */
    public float aspectRatio() {
        return (float) displayWidth / displayHeight;
    }

    /**
     * Copy with a new render scale for dynamic resolution. Keep dimensions even to avoid sampling
     * alignment issues on hardware.
     */
    public FrameSetup withRenderScale(float scale) {
        float s = scale <= 0.1f ? 0.1f : Math.min(scale, 1.0f);
        int w = Math.max(2, Math.round(displayWidth * s) / 2 * 2);
        int h = Math.max(2, Math.round(displayHeight * s) / 2 * 2);
        return new FrameSetup(displayWidth, displayHeight, w, h, (float) w / displayWidth,
                colorFormat, depthFormat, hdr, sampleCount, frameIndex, timing, resetRequested,
                uiSeparated, hudHidden, pauseScreenOpen, worldLoaded);
    }

    /**
     * Returns a copy with updated timing, used to fill measured GPU time without duplicating every
     * component of this immutable record.
     */
    public FrameSetup withTiming(FrameTiming newTiming) {
        return new FrameSetup(displayWidth, displayHeight, renderWidth, renderHeight, renderScale,
                colorFormat, depthFormat, hdr, sampleCount, frameIndex,
                newTiming == null ? timing : newTiming, resetRequested,
                uiSeparated, hudHidden, pauseScreenOpen, worldLoaded);
    }

    /**
     * Returns a copy with an updated frame index. Advancing the index must not implicitly request a
     * history reset: the old constant {@code forTest(1920,1080,0L)} setup both froze the index and reset
     * history every frame. Resets must reflect actual frame changes.
     */
    public FrameSetup withFrameIndex(long newFrameIndex) {
        return new FrameSetup(displayWidth, displayHeight, renderWidth, renderHeight, renderScale,
                colorFormat, depthFormat, hdr, sampleCount, newFrameIndex, timing, resetRequested,
                uiSeparated, hudHidden, pauseScreenOpen, worldLoaded);
    }

    /** Copy requesting a reset. */
    public FrameSetup withReset(boolean reset) {
        return new FrameSetup(displayWidth, displayHeight, renderWidth, renderHeight, renderScale,
                colorFormat, depthFormat, hdr, sampleCount, frameIndex, timing, reset,
                uiSeparated, hudHidden, pauseScreenOpen, worldLoaded);
    }

    /**
     * Returns a copy with the actual world-loaded state. The adapter updates this each frame from {@code
     * HostAdapter#sceneSnapshot()}; retaining the test factory's constant true value would incorrectly run
     * world-only pipelines in menus.
     */
    public FrameSetup withWorldLoaded(boolean loaded) {
        return new FrameSetup(displayWidth, displayHeight, renderWidth, renderHeight, renderScale,
                colorFormat, depthFormat, hdr, sampleCount, frameIndex, timing, resetRequested,
                uiSeparated, hudHidden, pauseScreenOpen, loaded);
    }

    /** Diagnostic summary. */
    public String describe() {
        return "frame=" + frameIndex + " display=" + displayWidth + "x" + displayHeight
                + " render=" + renderWidth + "x" + renderHeight
                + String.format(java.util.Locale.ROOT, " (scale=%.3f)", renderScale)
                + " color=" + colorFormat + " depth=" + depthFormat
                + (hdr ? " HDR" : "") + (resetRequested ? " RESET" : "");
    }

    /** Environment-independent frame setup for unit tests and headless pipeline smoke tests. */
    public static FrameSetup forTest(int width, int height, long frameIndex) {
        return new FrameSetup(width, height, width, height, 1f, GpuFormat.B8G8R8A8_UNORM,
                GpuFormat.D32_SFLOAT, false, 1, frameIndex, FrameTiming.unknown(frameIndex),
                frameIndex == 0L, false, false, false, true);
    }
}
