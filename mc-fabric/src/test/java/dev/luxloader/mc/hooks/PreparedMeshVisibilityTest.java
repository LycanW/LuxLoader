package dev.luxloader.mc.hooks;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedMeshVisibilityTest {
    @Test void hiddenBatchesCannotLeakIntoOtherEntitiesOrFollowingFrames() {
        assertTrue(PreparedMeshVisibility.cameraVisible());
        PreparedMeshVisibility.prepare(false, () -> {
            assertFalse(PreparedMeshVisibility.cameraVisible());
            PreparedMeshVisibility.prepare(true, () -> assertTrue(PreparedMeshVisibility.cameraVisible()));
            assertFalse(PreparedMeshVisibility.cameraVisible());
            assertThrows(IllegalStateException.class, () -> PreparedMeshVisibility.prepare(false, () -> {
                throw new IllegalStateException("Interrupted model preparation");
            }));
            assertFalse(PreparedMeshVisibility.cameraVisible());
        });
        assertTrue(PreparedMeshVisibility.cameraVisible());
    }
}
