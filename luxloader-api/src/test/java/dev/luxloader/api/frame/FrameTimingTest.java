package dev.luxloader.api.frame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unknown timing defaults and the effect of supplying measured GPU time. Unknown timing must
 * disable frame generation; valid timing must enable only multipliers that fit the display interval.
 * These cases distinguish missing timing data from incorrect gating arithmetic.
 */
class FrameTimingTest {

    /** A 240 Hz display has a 4.1666 ms frame interval. */
    private static final long DISPLAY_240HZ_NS = 4_166_666L;

    /** Rendering at 60 FPS takes 16.6666 ms per frame, allowing a theoretical 4x multiplier. */
    private static final long RENDER_60FPS_NS = 16_666_666L;

    private static FrameTiming withGpuTime(long gpuNs) {
        return new FrameTiming(0, 0L, gpuNs, DISPLAY_240HZ_NS, RENDER_60FPS_NS, 0L, 2, true);
    }

    @Test
    @DisplayName("Multiplier Is Constant Without Gpu Time")
    void multiplierIsConstantWithoutGpuTime() {
        // unknown() supplies zero GPU time.
        assertEquals(0L, FrameTiming.unknown(0).gpuFrameTimeNs(),
                "unknown() supplies zero GPU time");

        assertEquals(1, FrameTiming.unknown(0).maxFrameGenerationMultiplier(4),
                "Non-positive GPU time must return multiplier 1 for every requested limit");
    }

    @Test
    @DisplayName("Usefulness Is Constant Without Gpu Time")
    void usefulnessIsConstantWithoutGpuTime() {
        assertFalse(FrameTiming.unknown(0).frameGenerationUseful(),
                "Frame generation must remain disabled without GPU timing");
    }

    @Test
    @DisplayName("The Gate Itself Works Once Gpu Time Is Present")
    void theGateItselfWorksOnceGpuTimeIsPresent() {
        FrameTiming unfed = withGpuTime(0L);
        FrameTiming fed = withGpuTime(8_000_000L);

        // All fields except GPU time are identical.
        assertEquals(unfed.displayRefreshNs(), fed.displayRefreshNs());
        assertEquals(unfed.targetFrameTimeNs(), fed.targetFrameTimeNs());

        assertEquals(1, unfed.maxFrameGenerationMultiplier(4),
                "Missing GPU timing must return 1");
        assertEquals(4, fed.maxFrameGenerationMultiplier(4),
                "Valid timing permits 4x: 16.67 ms / 4.17 ms = 4.0. "
                        + "The gating formula works when supplied with timing data"
                        + " from GPU timestamps");

        assertFalse(unfed.frameGenerationUseful());
        assertTrue(fed.frameGenerationUseful(),
                "8 ms exceeds 4.17 ms, so interpolation is useful");
    }
}
