package dev.luxloader.api.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldFramePlanTest {
    @Test
    void legacyPlansLeaveCelestialsUnchangedAndCustomPlansCaptureOrientation() {
        assertEquals(CelestialRotation.IDENTITY,
                new WorldFramePlan(List.of(WorldFramePlan.Step.PIPELINE)).celestialRotation());
        assertEquals(CelestialRotation.IDENTITY, WorldFramePlan.builder().sky().pipeline().build().celestialRotation());
        var rotation = new CelestialRotation(0, 0, 1, 1);
        assertEquals(rotation, WorldFramePlan.builder().sky().pipeline().celestialRotation(rotation).build().celestialRotation());
        assertArrayEquals(new float[]{-1, 0, 0}, rotation.transform(new float[]{0, 1, 0}), 1e-6f);
        assertThrows(IllegalArgumentException.class, () -> new CelestialRotation(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CelestialRotation(Float.NaN, 0, 0, 1));
    }

    @Test
    void supportsOpaqueReplacementBeforeTransparency() {
        var plan = WorldFramePlan.builder().sky().preparedOpaqueFeatures()
                .pipeline().preparedTransparency().build();
        assertEquals(List.of(WorldFramePlan.Step.SKY,
                WorldFramePlan.Step.PREPARED_OPAQUE_FEATURES,
                WorldFramePlan.Step.PIPELINE,
                WorldFramePlan.Step.PREPARED_TRANSPARENCY), plan.steps());
    }

    @Test
    void rejectsPlansThatWouldDrawTheSamePreparedContentTwice() {
        assertThrows(IllegalArgumentException.class, () -> WorldFramePlan.builder()
                .preparedScene().pipeline().preparedTransparency().build());
        assertThrows(IllegalArgumentException.class, () -> WorldFramePlan.builder()
                .preparedFeatures().preparedOpaqueFeatures().pipeline().build());
        assertThrows(IllegalArgumentException.class, () -> WorldFramePlan.builder()
                .preparedOpaqueScene().preparedOpaqueFeatures().pipeline().build());
    }
}
