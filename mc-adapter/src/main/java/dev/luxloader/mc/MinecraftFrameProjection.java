package dev.luxloader.mc;

/** Render-thread copy of the exact world projection uploaded by GameRenderer. */
public final class MinecraftFrameProjection {
    private static float[] columns;
    private MinecraftFrameProjection() { }
    public static void clear() { columns = null; }
    public static void publish(float[] matrix) {
        if (matrix == null || matrix.length != 16) throw new IllegalArgumentException("Expected a 4x4 projection");
        columns = matrix.clone();
    }
    static float[] current() { return columns == null ? null : columns.clone(); }
}
