package dev.luxloader.mc.hooks;

/** Scoped to one section compiler invocation on its worker thread. */
public final class EmissionCapture {
    private EmissionCapture() { }
    public static final ThreadLocal<Float> CURRENT = new ThreadLocal<>();
}
