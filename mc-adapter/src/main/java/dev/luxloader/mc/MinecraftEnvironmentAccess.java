package dev.luxloader.mc;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.scene.SceneSnapshot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Reads the same prepared sky and fog state used by the host world pass. */
final class MinecraftEnvironmentAccess {
    private final Method instance, renderState, rain, thunder, dimension, dimensionType, ambient, time;
    private final Method x, y, z;
    private final Field level, renderer, levelState, sky, camera, partial, sunAngle, moonAngle;
    private final Field skyColor, skybox, fog, fogColor, fogStart, fogEnd;
    private final Consumer<String> warning;
    private boolean warned;

    MinecraftEnvironmentAccess(ClassLoader loader, Consumer<String> warning) throws ReflectiveOperationException {
        this.warning = warning;
        Class<?> mc = Class.forName("net.minecraft.client.Minecraft", false, loader);
        Class<?> rendererType = Class.forName("net.minecraft.client.renderer.GameRenderer", false, loader);
        Class<?> world = Class.forName("net.minecraft.world.level.Level", false, loader);
        instance = mc.getMethod("getInstance");
        level = mc.getField("level");
        renderer = mc.getField("gameRenderer");
        renderState = rendererType.getMethod("gameRenderState");
        levelState = renderState.getReturnType().getField("levelRenderState");
        sky = levelState.getType().getField("skyRenderState");
        camera = levelState.getType().getField("cameraRenderState");
        partial = levelState.getType().getField("worldPartialTicks");
        sunAngle = sky.getType().getField("sunAngle");
        moonAngle = sky.getType().getField("moonAngle");
        skyColor = sky.getType().getField("skyColor");
        skybox = sky.getType().getField("skybox");
        fog = camera.getType().getField("fogData");
        fogColor = fog.getType().getField("color");
        fogStart = fog.getType().getField("environmentalStart");
        fogEnd = fog.getType().getField("environmentalEnd");
        Class<?> vec = Class.forName("org.joml.Vector3fc", false, loader);
        x = vec.getMethod("x"); y = vec.getMethod("y"); z = vec.getMethod("z");
        rain = world.getMethod("getRainLevel", float.class);
        thunder = world.getMethod("getThunderLevel", float.class);
        dimension = world.getMethod("dimension");
        dimensionType = world.getMethod("dimensionType");
        ambient = dimensionType.getReturnType().getMethod("ambientLight");
        time = world.getMethod("getDefaultClockTime");
    }

    SceneSnapshot.Environment read() {
        try {
            Object mc = instance.invoke(null);
            Object world = level.get(mc);
            if (world == null) return SceneSnapshot.Environment.DEFAULT;
            Object state = levelState.get(renderState.invoke(renderer.get(mc)));
            Object skyState = sky.get(state);
            Object fogState = fog.get(camera.get(state));
            float tick = partial.getFloat(state);
            float rainLevel = ((Number) rain.invoke(world, tick)).floatValue();
            float thunderLevel = ((Number) thunder.invoke(world, tick)).floatValue();
            float[] sun = celestialDirection(sunAngle.getFloat(skyState));
            float[] moon = celestialDirection(moonAngle.getFloat(skyState));
            Object skyRgb = skyColor.get(skyState);
            float[] color = skyRgb == null ? new float[3] : new float[] {
                    ((Number) x.invoke(skyRgb)).floatValue(), ((Number) y.invoke(skyRgb)).floatValue(),
                    ((Number) z.invoke(skyRgb)).floatValue()};
            // The prepared skybox discriminates dimensions without naming dimensions.
            boolean celestial = "OVERWORLD".equals(String.valueOf(skybox.get(skyState)));
            float strength = celestial ? (1f - rainLevel * 0.8f) * (1f - thunderLevel * 0.8f) : 0f;
            float[] fogRgb = color;
            float start = -1f, end = -1f, distanceStart = -1f, distanceEnd = -1f, opacity = 1f;
            if (fogState != null) {
                Object rgba = fogColor.get(fogState);
                if (rgba != null) fogRgb = new float[] {
                        rgba.getClass().getField("x").getFloat(rgba),
                        rgba.getClass().getField("y").getFloat(rgba),
                        rgba.getClass().getField("z").getFloat(rgba)};
                start = fogStart.getFloat(fogState);
                end = fogEnd.getFloat(fogState);
                distanceStart = fogState.getClass().getField("renderDistanceStart").getFloat(fogState);
                distanceEnd = fogState.getClass().getField("renderDistanceEnd").getFloat(fogState);
                if (rgba != null) opacity = rgba.getClass().getField("w").getFloat(rgba);

            }
            if (!celestial) color = fogRgb.clone();
            return new SceneSnapshot.Environment(color, fogRgb, start, end, sun,
                    new float[] {1f, 0.98f, 0.92f, strength}, moon,
                    ((Number) ambient.invoke(dimensionType.invoke(world))).floatValue(),
                    rainLevel, thunderLevel, ((Number) time.invoke(world)).longValue(),
                    String.valueOf(dimension.invoke(world)), submerged(camera.get(state)), distanceStart, distanceEnd, opacity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) { warned = true; warning.accept(tr("Failed to read environment state: ") + e); }
            return SceneSnapshot.Environment.DEFAULT;
        }
    }

    static float[] celestialDirection(float radians) {
        // SkyRenderer uses rotateY(-pi/2) * rotateX(angle) on (0, 1, 0).
        return new float[] {-(float) Math.sin(radians), (float) Math.cos(radians), 0f};
    }

    private static boolean submerged(Object camera) throws ReflectiveOperationException {
        String medium = String.valueOf(camera.getClass().getField("fogType").get(camera));
        return medium.equals("WATER") || medium.equals("LAVA");
    }
}
