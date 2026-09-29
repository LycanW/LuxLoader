package dev.luxloader.mc.hooks;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.luxloader.api.pipeline.CelestialRotation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedSkyOrientationTest {
    @Test void nestedAndFailedSkyDrawsCannotLeakOrientationIntoVanilla() {
        var outer = new CelestialRotation(.25f, 0, 0, 1);
        var inner = new CelestialRotation(-.25f, 0, 0, 1);
        assertEquals(CelestialRotation.IDENTITY, PreparedSkyOrientation.current());
        PreparedSkyOrientation.draw(outer, () -> {
            assertEquals(outer, PreparedSkyOrientation.current());
            assertThrows(IllegalStateException.class, () -> PreparedSkyOrientation.draw(inner, () -> {
                assertEquals(inner, PreparedSkyOrientation.current());
                throw new IllegalStateException("test");
            }));
            assertEquals(outer, PreparedSkyOrientation.current());
        });
        assertEquals(CelestialRotation.IDENTITY, PreparedSkyOrientation.current());
    }

    @Test void preparedCelestialPoseMatchesWorldLightDirectionsThroughoutTheDay() {
        var rotation = new CelestialRotation((float) Math.sin(Math.PI/12), 0, 0, (float) Math.cos(Math.PI/12));
        for (int step = 0; step < 24; step++) {
            float angle = (float) (step * Math.PI / 12);
            var pose = new PoseStack();
            pose.pushPose();
            pose.rotate(new Quaternionf(rotation.x(), rotation.y(), rotation.z(), rotation.w()));
            pose.rotateDegrees(Axis.YP, -90);
            pose.rotate(Axis.XP, angle);
            Vector3f visible = pose.last().pose().transformDirection(new Vector3f(0, 1, 0));
            float[] light = rotation.transform(new float[]{-(float) Math.sin(angle), (float) Math.cos(angle), 0});
            assertArrayEquals(light, new float[]{visible.x, visible.y, visible.z}, 1e-6f);
            pose.popPose();
            assertEquals(new org.joml.Matrix4f(), pose.last().pose());
        }
    }
}
