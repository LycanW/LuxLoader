package dev.luxloader.api.scene;

/** Actual published atlas frame, including the host's interpolation phase. */
public record TextureAnimation(int columns, int rows, int current, int next, float blend) {
    public static final TextureAnimation STATIC = new TextureAnimation(1, 1, 0, 0, 0);

    public TextureAnimation {
        if (columns < 1 || rows < 1 || current < 0 || next < 0
                || current >= (long) columns * rows || next >= (long) columns * rows
                || !Float.isFinite(blend) || blend < 0 || blend > 1)
            throw new IllegalArgumentException("Invalid texture animation frame");
    }
}
