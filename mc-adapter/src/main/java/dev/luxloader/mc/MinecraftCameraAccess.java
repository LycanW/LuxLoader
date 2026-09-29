package dev.luxloader.mc;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.frame.CameraParams;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Reads 26.3's prepared camera state; never scans or meshes the game world. */
final class MinecraftCameraAccess {

    private final Consumer<String> warning;
    private final Method getInstance;
    private final Field level;
    private final Field gameRenderer;
    private final Method gameRenderState;
    private final Field levelRenderState;
    private final Field cameraRenderState;
    private final Field position;
    private final Field rotation;
    private final Field projection;
    private final Method vecX;
    private final Method vecY;
    private final Method vecZ;
    private final Method[] matrix;
    private final Method matrixGet;
    private final Method mainCamera;
    private final Method getFov;
    private boolean warned;

    MinecraftCameraAccess(ClassLoader loader, Consumer<String> warning) throws ReflectiveOperationException {
        this.warning = warning;
        Class<?> minecraft = load(loader, "net.minecraft.client.Minecraft");
        Class<?> renderer = load(loader, "net.minecraft.client.renderer.GameRenderer");
        Class<?> gameState = load(loader, "net.minecraft.client.renderer.state.GameRenderState");
        Class<?> levelState = load(loader, "net.minecraft.client.renderer.state.level.LevelRenderState");
        Class<?> cameraState = load(loader, "net.minecraft.client.renderer.state.level.CameraRenderState");
        Class<?> matrixType = load(loader, "org.joml.Matrix4f");
        Class<?> vec = load(loader, "net.minecraft.world.phys.Vec3");
        Class<?> camera = load(loader, "net.minecraft.client.Camera");
        getInstance = minecraft.getMethod("getInstance");
        level = minecraft.getField("level");
        gameRenderer = minecraft.getField("gameRenderer");
        gameRenderState = renderer.getMethod("gameRenderState");
        levelRenderState = gameState.getField("levelRenderState");
        cameraRenderState = levelState.getField("cameraRenderState");
        position = cameraState.getField("pos");
        rotation = cameraState.getField("viewRotationMatrix");
        projection = cameraState.getField("projectionMatrix");
        vecX = vec.getMethod("x");
        vecY = vec.getMethod("y");
        vecZ = vec.getMethod("z");
        matrix = new Method[] {
                matrixType.getMethod("m00"), matrixType.getMethod("m10"), matrixType.getMethod("m20"),
                matrixType.getMethod("m01"), matrixType.getMethod("m11"), matrixType.getMethod("m21"),
                matrixType.getMethod("m02"), matrixType.getMethod("m12"), matrixType.getMethod("m22")
        };
        matrixGet = matrixType.getMethod("get", float[].class);
        mainCamera = renderer.getMethod("mainCamera");
        getFov = camera.getMethod("getFov");
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    Object worldIdentity() {
        try {
            return level.get(getInstance.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            report(e);
            return null;
        }
    }

    boolean levelPresent() {
        return worldIdentity() != null;
    }

    CameraParams currentCamera() {
        try {
            Object minecraft = getInstance.invoke(null);
            if (minecraft == null || level.get(minecraft) == null) {
                return CameraParams.identity();
            }
            Object renderer = gameRenderer.get(minecraft);
            Object state = cameraRenderState.get(levelRenderState.get(gameRenderState.invoke(renderer)));
            if (state == null) {
                return CameraParams.identity();
            }
            Object p = position.get(state);
            Object r = rotation.get(state);
            Object projectionMatrix = projection.get(state);
            if (p == null || r == null || projectionMatrix == null) {
                return CameraParams.identity();
            }
            double x = number(vecX, p);
            double y = number(vecY, p);
            double z = number(vecZ, p);
            double[] rot = new double[9];
            for (int i = 0; i < 9; i++) {
                rot[i] = number(matrix[i], r);
            }
            float[] view = {
                    (float) rot[0], (float) rot[1], (float) rot[2],
                    (float) -(rot[0] * x + rot[1] * y + rot[2] * z),
                    (float) rot[3], (float) rot[4], (float) rot[5],
                    (float) -(rot[3] * x + rot[4] * y + rot[5] * z),
                    (float) rot[6], (float) rot[7], (float) rot[8],
                    (float) -(rot[6] * x + rot[7] * y + rot[8] * z),
                    0f, 0f, 0f, 1f
            };
            float[] columns = new float[16];
            matrixGet.invoke(projectionMatrix, (Object) columns);
            float[] proj = new float[16];
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
                    proj[row * 4 + col] = columns[col * 4 + row];
                }
            }
            float fov = fovRadians(renderer, projectionMatrix);
            float[] planes = depthPlanes(proj);
            // The base state omits view bobbing/hurt/portal transforms. Match the
            // projection uploaded for this world frame, while retaining base clip planes.
            float[] uploaded = MinecraftFrameProjection.current();
            if (uploaded != null) {
                for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++)
                    proj[row * 4 + col] = uploaded[col * 4 + row];
            }
            return new CameraParams(view, proj, invert4x4(proj),
                    0f, 0f, 0f, 0f, planes[0], planes[1], false, fov);
        } catch (ReflectiveOperationException | RuntimeException e) {
            report(e);
            return CameraParams.identity();
        }
    }

    /** Vulkan reverse-Z perspective: depth = -m22 - m23 / viewZ. */
    static float[] depthPlanes(float[] projection) {
        float a = projection[10], b = projection[11];
        float near = b / (1f + a);
        float far = a == 0f ? Float.POSITIVE_INFINITY : b / a;
        if (!(near > 0) || !(far > near))
            throw new IllegalArgumentException("Invalid reverse-Z perspective depth planes");
        return new float[]{near, far};
    }

    private float fovRadians(Object renderer, Object projectionMatrix) throws ReflectiveOperationException {
        double m11 = number(matrix[4], projectionMatrix);
        if (m11 > .5 && m11 < 114.0) {
            return (float) (2.0 * Math.atan(1.0 / m11));
        }
        Object camera = mainCamera.invoke(renderer);
        if (camera != null) {
            float degrees = ((Number) getFov.invoke(camera)).floatValue();
            if (degrees > 1f && degrees < 179f) {
                return (float) Math.toRadians(degrees);
            }
        }
        return (float) Math.toRadians(70.0);
    }

    private static double number(Method method, Object target) throws ReflectiveOperationException {
        return ((Number) method.invoke(target)).doubleValue();
    }

    private void report(Exception failure) {
        if (!warned) {
            warned = true;
            warning.accept(tr("Failed to read camera state: ") + failure);
        }
    }

    private static float[] invert4x4(float[] matrix) {
        double[][] a = new double[4][8];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                a[row][col] = matrix[row * 4 + col];
            }
            a[row][row + 4] = 1.0;
        }
        for (int col = 0; col < 4; col++) {
            int pivot = col;
            for (int row = col + 1; row < 4; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-12) {
                return null;
            }
            double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            double divisor = a[col][col];
            for (int j = 0; j < 8; j++) {
                a[col][j] /= divisor;
            }
            for (int row = 0; row < 4; row++) {
                if (row == col) {
                    continue;
                }
                double factor = a[row][col];
                for (int j = 0; j < 8; j++) {
                    a[row][j] -= factor * a[col][j];
                }
            }
        }
        float[] inverse = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                inverse[row * 4 + col] = (float) a[row][col + 4];
            }
        }
        return inverse;
    }
}
