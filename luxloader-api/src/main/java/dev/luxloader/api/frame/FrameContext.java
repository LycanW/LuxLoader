package dev.luxloader.api.frame;

import dev.luxloader.api.gpu.GpuCommands;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.gpu.GpuQueue;
import dev.luxloader.api.scene.SceneSnapshot;
import dev.luxloader.api.scene.SceneGeometryFeed;

import java.util.Objects;

/**
 * Complete per-frame context supplied to RenderPipeline.renderFrame. Valid only for that call; never
 * retain it or its mutable objects across frames. Copy retained data into pipeline-owned resources.
 * Contains available textures, camera, timing, device, command recorder, queue and UI/world state.
 * scene exposes camera, geometry and environment to world renderers and is never null; absent data
 * uses SceneSnapshot.empty().
 */
public record FrameContext(
        FrameTextures textures,
        CameraParams camera,
        FrameTiming timing,
        GpuDevice device,
        GpuCommands commands,
        GpuQueue queue,
        boolean hudHidden,
        boolean pauseScreenOpen,
        boolean worldLoaded,
        boolean screenSpaceUi,
        SceneSnapshot scene,
        CameraParams previousCamera,
        boolean cameraCut,
        SceneGeometryFeed geometryFeed) {

    /** Compatibility constructor for plugins compiled against the original frame contract. */
    public FrameContext(FrameTextures textures, CameraParams camera, FrameTiming timing,
                        GpuDevice device, GpuCommands commands, GpuQueue queue,
                        boolean hudHidden, boolean pauseScreenOpen, boolean worldLoaded,
                        boolean screenSpaceUi, SceneSnapshot scene) {
        this(textures, camera, timing, device, commands, queue, hudHidden,
                pauseScreenOpen, worldLoaded, screenSpaceUi, scene, null, true, null);
    }

    public FrameContext(FrameTextures textures, CameraParams camera, FrameTiming timing,
                        GpuDevice device, GpuCommands commands, GpuQueue queue,
                        boolean hudHidden, boolean pauseScreenOpen, boolean worldLoaded,
                        boolean screenSpaceUi, SceneSnapshot scene,
                        CameraParams previousCamera, boolean cameraCut) {
        this(textures, camera, timing, device, commands, queue, hudHidden,
                pauseScreenOpen, worldLoaded, screenSpaceUi, scene,
                previousCamera, cameraCut, null);
    }

    public FrameContext {
        textures = textures == null ? FrameTextures.EMPTY : textures;
        scene = scene == null ? SceneSnapshot.empty(0L) : scene;
        // camera aliases scene.camera(). Fill a null argument from the scene to avoid inconsistent camera sources.
        camera = camera == null ? scene.camera() : camera;
        timing = timing == null ? FrameTiming.unknown(0L) : timing;
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(queue, "queue");
    }

    /** Whether frame pacing can change: an active world without a menu or loading screen. */
    public boolean isGameplayFrame() {
        return worldLoaded && !pauseScreenOpen;
    }

    /** Whether UI needs a separate path to preserve text sharpness during upscaling. */
    public boolean shouldSeparateUi() {
        return screenSpaceUi || (!hudHidden && worldLoaded);
    }

    /** Key frame after a scene change, teleport or resize; temporal backends must reset history. */
    public boolean isKeyFrame() {
        return cameraCut || textures.history() == null || textures.history().isNull();
    }

    /** Temporal effects must check this before using the previous matrices. */
    public boolean hasCameraHistory() {
        return !cameraCut && previousCamera != null
                && camera.hasProjection() && previousCamera.hasProjection();
    }
}
