package dev.luxloader.api.frame;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * Frame timing for frame generation and pacing. Times are nanoseconds, matching Vulkan present timing
 * extensions. frameIndex increases monotonically; cpuFrameTimeNs measures CPU submission,
 * gpuFrameTimeNs measures prior GPU execution when available (otherwise zero). displayRefreshNs and
 * targetFrameTimeNs specify refresh and pacing intervals; presentTimeNs is the expected presentation
 * time. inFlightFrames and vsync describe queueing and synchronization.
 */
public record FrameTiming(
        long frameIndex,
        long cpuFrameTimeNs,
        long gpuFrameTimeNs,
        long displayRefreshNs,
        long targetFrameTimeNs,
        long presentTimeNs,
        int inFlightFrames,
        boolean vsync) {

    public FrameTiming {
        if (frameIndex < 0) {
            throw new IllegalArgumentException(tr("frameIndex must not be negative"));
        }
        if (inFlightFrames < 0) {
            throw new IllegalArgumentException(tr("inFlightFrames must not be negative"));
        }
    }

    /** Default 60 Hz timing without historical measurements for adapters lacking timing data. */
    public static FrameTiming unknown(long frameIndex) {
        return new FrameTiming(frameIndex, 0L, 0L, 16_666_666L, 16_666_666L, 0L, 2, true);
    }

    /** Advance the rendered frame identity while retaining the measured timings. */
    public FrameTiming withFrameIndex(long newFrameIndex) {
        return newFrameIndex == frameIndex ? this : new FrameTiming(newFrameIndex, cpuFrameTimeNs,
                gpuFrameTimeNs, displayRefreshNs, targetFrameTimeNs, presentTimeNs, inFlightFrames, vsync);
    }

    /** Derive the refresh interval from a frequency. */
    public static long refreshNs(double hz) {
        if (hz <= 0) {
            throw new IllegalArgumentException(tr("Refresh rate must be positive: ") + hz);
        }
        return (long) (1_000_000_000.0 / hz);
    }

    /** Measured frame rate from GPU time; zero when unavailable. */
    public double gpuFrameRate() {
        return gpuFrameTimeNs > 0 ? 1_000_000_000.0 / gpuFrameTimeNs : 0.0;
    }

    /** Display refresh rate. */
    public double displayRefreshRate() {
        return displayRefreshNs > 0 ? 1_000_000_000.0 / displayRefreshNs : 0.0;
    }

    /**
     * Maximum generated-frame multiplier per display interval. For 4x, displayRefresh/renderFps must reach
     * 4 to avoid judder. Without measured GPU time this returns 1.
     * @param maxMultiplier maximum supported multiplier
     */
    public int maxFrameGenerationMultiplier(int maxMultiplier) {
        if (gpuFrameTimeNs <= 0 || displayRefreshNs <= 0) {
            return 1;
        }
        double ratio = (double) targetFrameTimeNs / displayRefreshNs;
        int supported = (int) Math.floor(ratio);
        return Math.max(1, Math.min(maxMultiplier, supported));
    }

    /**
     * Whether rendering below refresh rate makes frame generation useful. Returns false when GPU timing is
     * unavailable.
     */
    public boolean frameGenerationUseful() {
        return gpuFrameTimeNs > displayRefreshNs && displayRefreshNs > 0;
    }

    /**
     * Copy with measured GPU frame time. Hosts must supply actual timestamp-query results; otherwise
     * unknown timing leaves generation disabled. Convert raw GPU ticks using
     * GpuCapabilities.timestampPeriod. Never infer generation benefits from an absent measurement.
     */
    public FrameTiming withGpuTime(long newGpuTimeNs) {
        return new FrameTiming(frameIndex, cpuFrameTimeNs, newGpuTimeNs, displayRefreshNs,
                targetFrameTimeNs, presentTimeNs, inFlightFrames, vsync);
    }
}
