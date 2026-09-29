package dev.luxloader.mc;

import dev.luxloader.api.scene.DynamicSceneMesh;
import java.util.ArrayList;
import java.util.List;

/** Render-thread publication of prepared feature geometry; cleared at each world frame. */
public final class MinecraftDynamicScene {
    private static final List<DynamicSceneMesh> meshes = new ArrayList<>();
    private MinecraftDynamicScene() { }
    public static void beginFrame() { meshes.clear(); }
    public static void publish(DynamicSceneMesh mesh) { meshes.add(mesh); }
    public static List<DynamicSceneMesh> snapshot() { return List.copyOf(meshes); }
}
