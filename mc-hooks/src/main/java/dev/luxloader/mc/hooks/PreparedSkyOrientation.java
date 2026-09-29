package dev.luxloader.mc.hooks;

import dev.luxloader.api.pipeline.CelestialRotation;

/** Scopes a captured sky orientation to its scheduled draw, including exceptional exits. */
public final class PreparedSkyOrientation {
    private static final ThreadLocal<CelestialRotation> CURRENT =
            ThreadLocal.withInitial(() -> CelestialRotation.IDENTITY);

    private PreparedSkyOrientation() { }

    public static CelestialRotation current() { return CURRENT.get(); }

    public static void draw(CelestialRotation rotation, Runnable draws) {
        CelestialRotation previous = CURRENT.get();
        CURRENT.set(rotation);
        try {
            draws.run();
        } finally {
            if (previous.equals(CelestialRotation.IDENTITY)) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
