package dev.luxloader.mc.hooks;

/** Source light emission, distinct from the received lightmap value. */
public interface SourceEmission {
    float[] luxloader$emission();
    void luxloader$emission(float[] values);
}
