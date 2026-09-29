package dev.luxloader.api.scene;

/** A source sprite and its measured rectangle in a host atlas. */
public record AtlasSpriteRef(TextureRef texture, float u0, float v0, float u1, float v1, boolean fluidSurface) {
    public AtlasSpriteRef(TextureRef texture, float u0, float v0, float u1, float v1) {
        this(texture, u0, v0, u1, v1, false);
    }
    public AtlasSpriteRef {
        if (texture == null || !(u1 > u0) || !(v1 > v0)) {
            throw new IllegalArgumentException("Invalid atlas sprite rectangle");
        }
    }

    public boolean contains(float u, float v) {
        return u >= u0 && u < u1 && v >= v0 && v < v1;
    }
}
