package dev.luxloader.mc;

/** Semantic provenance from the host fluid models, never inferred from texture names. */
public final class MinecraftFluidSurfaces {
    private static final java.util.Set<String> textures = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
    private MinecraftFluidSurfaces() { }
    public static void register(String texture) { if (textures.add(texture)) revision.incrementAndGet(); }
    public static boolean contains(String texture) { return textures.contains(texture); }
    public static long revision() { return revision.get(); }
    public static void clear() { textures.clear(); revision.incrementAndGet(); }
}
