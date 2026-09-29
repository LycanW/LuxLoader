package dev.luxloader.mc.hooks;

/** Carries camera visibility through one synchronous feature preparation batch. */
public final class PreparedMeshVisibility {
    private static final ThreadLocal<Boolean> CAMERA_VISIBLE = ThreadLocal.withInitial(() -> true);

    private PreparedMeshVisibility() { }

    public static boolean cameraVisible() { return CAMERA_VISIBLE.get(); }

    public static void prepare(boolean cameraVisible, Runnable preparation) {
        boolean previous = CAMERA_VISIBLE.get();
        CAMERA_VISIBLE.set(cameraVisible);
        try {
            preparation.run();
        } finally {
            if (previous) CAMERA_VISIBLE.remove();
            else CAMERA_VISIBLE.set(false);
        }
    }
}
