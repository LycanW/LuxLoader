package dev.luxloader.mc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftCameraDepthTest {
    @Test void uploadedProjectionIsCopiedAndScopedToWorldFrame() {
        float[] projection = new float[16]; projection[12] = .2f;
        MinecraftFrameProjection.publish(projection);
        projection[12] = 10;
        assertEquals(.2f, MinecraftFrameProjection.current()[12]);
        MinecraftFrameProjection.current()[12] = 20;
        assertEquals(.2f, MinecraftFrameProjection.current()[12]);
        MinecraftFrameProjection.clear();
        assertNull(MinecraftFrameProjection.current());
    }

    @Test void derivesInfiniteReverseZAndFiniteReverseZ() {
        float[] matrix = new float[16];
        matrix[11] = .05f; matrix[14] = -1;
        assertArrayEquals(new float[]{.05f, Float.POSITIVE_INFINITY}, MinecraftCameraAccess.depthPlanes(matrix));
        matrix[10] = .2f / (800f - .2f);
        matrix[11] = 800f * matrix[10];
        assertArrayEquals(new float[]{.2f, 800f}, MinecraftCameraAccess.depthPlanes(matrix), .0001f);
    }
}
