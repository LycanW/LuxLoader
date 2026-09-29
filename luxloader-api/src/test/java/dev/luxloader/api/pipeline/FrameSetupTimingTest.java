package dev.luxloader.api.pipeline;

import dev.luxloader.api.frame.FrameTiming;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameSetupTimingTest {
    @Test void frameAdvancementAlsoAdvancesPluginTimingWithoutLosingMeasurements() {
        var timing=new FrameTiming(0,123,456,789,987,654,3,false);
        var setup=FrameSetup.forTest(854,480,0).withTiming(timing);
        for(long frame=1;frame<=32;frame++) {
            setup=setup.withFrameIndex(frame).withReset(false);
            assertEquals(frame,setup.timing().frameIndex());
            assertEquals(new FrameTiming(frame,123,456,789,987,654,3,false),setup.timing());
            assertFalse(setup.resetRequested());
        }
    }

    @Test void resizeAndLateTimingReadbackCannotRewindTheRenderedFrame() {
        var setup=FrameSetup.forTest(854,480,20).withTiming(FrameTiming.unknown(7).withGpuTime(1234));
        var resized=setup.withRenderScale(.5f);
        assertEquals(20,resized.frameIndex());
        assertEquals(20,resized.timing().frameIndex());
        assertEquals(1234,resized.timing().gpuFrameTimeNs());
    }
}
