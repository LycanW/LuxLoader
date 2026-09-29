package dev.luxloader.api.scene;

/** A generic source sprite in the active resource packs, before atlas packing. */
public record TextureRef(String id, int width, int height) {
    public TextureRef {
        if (id == null || id.isBlank() || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Texture source needs an id and positive size");
        }
    }
}
