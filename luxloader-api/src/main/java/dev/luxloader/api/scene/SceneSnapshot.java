package dev.luxloader.api.scene;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.frame.CameraParams;
import dev.luxloader.api.gpu.ImageHandle;

import java.util.List;
import java.util.Map;

/**
 * Neutral scene snapshot containing geometry, camera and environment. The game adapter extracts data,
 * the kernel forwards it and the plugin renders it. Rendering, culling and sorting remain plugin
 * decisions. Empty collections and unavailable optional fields are valid in menus/loading screens.
 * @param camera camera, including a fallback for menus
 * @param chunks available host GPU geometry
 * @param environment sky, sun, fog and other environment parameters
 * @param textures optional images by semantic name
 * @param frameIndex frame index
 * @param worldLoaded whether a world is loaded
 * @param hudHidden whether the HUD is hidden
 * @param totalVertices cached total vertex count for budgeting
 * @param totalIndices cached total index count
 * @param cpuMeshes raw geometry before upload; adapters can extract without GPU allocators, and
 * plugins upload through their scoped resources with required usages such as
 * ACCEL_STRUCT_BUILD_INPUT/device address. CPU and GPU geometry may coexist or both be empty
 * @param materials material table indexed by the CPU vertex material component, allowing material
 * changes without rebuilding geometry
 */
public record SceneSnapshot(
        CameraParams camera,
        List<MeshChunk> chunks,
        Environment environment,
        Map<String, ImageHandle> textures,
        long frameIndex,
        boolean worldLoaded,
        boolean hudHidden,
        long totalVertices,
        long totalIndices,
        List<CpuMesh> cpuMeshes,
        List<MaterialDef> materials,
        List<SceneImage> images,
        List<CompiledSceneMesh> compiledMeshes,
        long geometryRevision,
        List<AtlasSpriteRef> atlasSprites,
        List<DynamicSceneMesh> dynamicMeshes) {

    public SceneSnapshot(CameraParams camera, List<MeshChunk> chunks, Environment environment,
                         Map<String, ImageHandle> textures, long frameIndex, boolean worldLoaded,
                         boolean hudHidden, long totalVertices, long totalIndices,
                         List<CpuMesh> cpuMeshes, List<MaterialDef> materials,
                         List<SceneImage> images, List<CompiledSceneMesh> compiledMeshes,
                         long geometryRevision, List<AtlasSpriteRef> atlasSprites) {
        this(camera, chunks, environment, textures, frameIndex, worldLoaded, hudHidden,
                totalVertices, totalIndices, cpuMeshes, materials, images, compiledMeshes,
                geometryRevision, atlasSprites, List.of());
    }

    /** Source-compatible constructor for hosts without compiled mesh extraction. */
    public SceneSnapshot(CameraParams camera, List<MeshChunk> chunks, Environment environment,
                         Map<String, ImageHandle> textures, long frameIndex, boolean worldLoaded,
                         boolean hudHidden, long totalVertices, long totalIndices,
                         List<CpuMesh> cpuMeshes, List<MaterialDef> materials,
                         List<SceneImage> images) {
        this(camera, chunks, environment, textures, frameIndex, worldLoaded, hudHidden,
                totalVertices, totalIndices, cpuMeshes, materials, images, List.of(), 0L, List.of());
    }

    public SceneSnapshot(CameraParams camera, List<MeshChunk> chunks, Environment environment,
                         Map<String, ImageHandle> textures, long frameIndex, boolean worldLoaded,
                         boolean hudHidden, long totalVertices, long totalIndices,
                         List<CpuMesh> cpuMeshes, List<MaterialDef> materials,
                         List<SceneImage> images, List<CompiledSceneMesh> compiledMeshes,
                         long geometryRevision) {
        this(camera, chunks, environment, textures, frameIndex, worldLoaded, hudHidden,
                totalVertices, totalIndices, cpuMeshes, materials, images,
                compiledMeshes, geometryRevision, List.of());
    }

    /**
     * Environment parameters.
     * @param skyColor RGB sky/environment color
     * @param fogColor RGB fog color
     * @param fogStart fog start distance; negative disables fog
     * @param fogEnd fog end distance
     * @param sunDirection world-space unit sun direction
     * @param sunColor RGB sun color and alpha intensity
     * @param moonDirection moon direction
     * @param ambientLight ambient intensity in [0,1]
     * @param rainStrength rain intensity in [0,1]
     * @param thunderStrength thunder intensity in [0,1]
     * @param timeOfDayTicks game time for the day/night cycle
     * @param dimensionName dimension identifier, e.g. minecraft:overworld
     */
    public record Environment(
            float[] skyColor,
            float[] fogColor,
            float fogStart,
            float fogEnd,
            float[] sunDirection,
            float[] sunColor,
            float[] moonDirection,
            float ambientLight,
            float rainStrength,
            float thunderStrength,
            long timeOfDayTicks,
            String dimensionName,
            boolean cameraSubmerged,
            float renderDistanceFogStart,
            float renderDistanceFogEnd,
            float fogOpacity) {

        public Environment(float[] skyColor, float[] fogColor, float fogStart, float fogEnd,
                           float[] sunDirection, float[] sunColor, float[] moonDirection,
                           float ambientLight, float rainStrength, float thunderStrength,
                           long timeOfDayTicks, String dimensionName, boolean cameraSubmerged) {
            this(skyColor, fogColor, fogStart, fogEnd, sunDirection, sunColor, moonDirection,
                    ambientLight, rainStrength, thunderStrength, timeOfDayTicks, dimensionName,
                    cameraSubmerged, -1f, -1f, 1f);
        }

        public Environment(float[] skyColor, float[] fogColor, float fogStart, float fogEnd,
                           float[] sunDirection, float[] sunColor, float[] moonDirection,
                           float ambientLight, float rainStrength, float thunderStrength,
                           long timeOfDayTicks, String dimensionName) {
            this(skyColor, fogColor, fogStart, fogEnd, sunDirection, sunColor, moonDirection,
                    ambientLight, rainStrength, thunderStrength, timeOfDayTicks, dimensionName, false);
        }

        /** Default environment for menus or uninitialized scenes. */
        public static final Environment DEFAULT = new Environment(
                new float[] {0.5f, 0.7f, 1.0f},
                new float[] {0.6f, 0.7f, 0.9f},
                -1f, -1f,
                new float[] {0f, 1f, 0f},
                new float[] {1f, 0.98f, 0.92f, 1f},
                new float[] {0f, -1f, 0f},
                1.0f, 0f, 0f, 6000L, "");

        public Environment {
            skyColor = skyColor == null ? new float[] {0.5f, 0.7f, 1.0f} : skyColor.clone();
            fogColor = fogColor == null ? new float[] {0.6f, 0.7f, 0.9f} : fogColor.clone();
            sunDirection = sunDirection == null ? new float[] {0f, 1f, 0f} : sunDirection.clone();
            sunColor = sunColor == null ? new float[] {1f, 1f, 1f, 1f} : sunColor.clone();
            moonDirection = moonDirection == null ? new float[] {0f, -1f, 0f} : moonDirection.clone();
            dimensionName = dimensionName == null ? "" : dimensionName;
        }

        /** Whether fog is enabled. */
        public boolean hasFog() {
            return fogOpacity > 0 && ((fogStart >= 0f && fogEnd > fogStart)
                    || (renderDistanceFogStart >= 0f && renderDistanceFogEnd > renderDistanceFogStart));
        }

        /** Whether the sun is below the horizon. */
        public boolean isNight() {
            return sunDirection.length > 1 && sunDirection[1] < 0f;
        }

        /** Whether weather affects the scene. */
        public boolean isWeatherActive() {
            return rainStrength > 0.01f || thunderStrength > 0.01f;
        }
    }

    /** Defensively freezes mutable collections because snapshots may be read across render/plugin threads. */
    public SceneSnapshot {
        camera = camera == null ? CameraParams.identity() : camera;
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        environment = environment == null ? Environment.DEFAULT : environment;
        textures = textures == null ? Map.of() : Map.copyOf(textures);
        cpuMeshes = cpuMeshes == null ? List.of() : List.copyOf(cpuMeshes);
        materials = materials == null ? List.of() : List.copyOf(materials);
        images = images == null ? List.of() : List.copyOf(images);
        compiledMeshes = compiledMeshes == null ? List.of() : List.copyOf(compiledMeshes);
        atlasSprites = atlasSprites == null ? List.of() : List.copyOf(atlasSprites);
        dynamicMeshes = dynamicMeshes == null ? List.of() : List.copyOf(dynamicMeshes);
        if (totalVertices < 0 || totalIndices < 0) {
            throw new IllegalArgumentException(tr("Total vertex/index counts must not be negative"));
        }
    }

    /** Empty scene for menus/loading screens. */
    public static SceneSnapshot empty(long frameIndex) {
        return new SceneSnapshot(CameraParams.identity(), List.of(), Environment.DEFAULT,
                Map.of(), frameIndex, false, false, 0L, 0L, List.of(), List.of(), List.of());
    }

    /** Whether a world and at least one geometry chunk are available to draw. */
    public boolean isRenderable() {
        return worldLoaded && hasGeometry();
    }

    /** Filters geometry chunks by category. */
    public List<MeshChunk> chunksOf(MeshChunk.Kind kind) {
        return chunks.stream().filter(c -> c.kind() == kind).toList();
    }

    /** Opaque geometry, drawn first for depth prepass. */
    public List<MeshChunk> opaqueChunks() {
        return chunks.stream().filter(MeshChunk::opaque).toList();
    }

    /** Translucent geometry, drawn later with sorting. */
    public List<MeshChunk> translucentChunks() {
        return chunks.stream().filter(c -> !c.opaque()).toList();
    }

    /** Estimated geometry GPU memory in bytes for this frame. */
    public long geometryBytes() {
        long bytes = 0;
        for (MeshChunk chunk : chunks) {
            bytes += chunk.vertexBytes() + chunk.indexBytes();
        }
        for (CompiledSceneMesh mesh : compiledMeshes) {
            bytes += mesh.vertexBytes() + mesh.indexBytes();
        }
        return bytes;
    }

    /** Diagnostic summary. */
    public String describe() {
        return "frame=" + frameIndex + tr(" sections=") + chunks.size()
                + tr(" vertices=") + totalVertices + tr(" indices=") + totalIndices
                + (cpuMeshes.isEmpty() ? "" : tr(" CPU meshes=") + cpuMeshes.size() + "/" + cpuMeshTriangles() + tr("triangles"))
                + (compiledMeshes.isEmpty() ? "" : tr(" compiled meshes=") + compiledMeshes.size()
                + tr(" revision=") + geometryRevision)
                + (worldLoaded ? "" : tr(" (outside a world)"))
                + tr(" environment=") + (environment.hasFog() ? tr("fog") : tr("no fog"));
    }

    /** Total CPU geometry triangle count. */
    public long cpuMeshTriangles() {
        long triangles = 0;
        for (CpuMesh mesh : cpuMeshes) {
            triangles += mesh.triangleCount();
        }
        return triangles;
    }

    /** Whether drawable geometry exists in GPU handles or CPU arrays. */
    public boolean hasGeometry() {
        return !chunks.isEmpty() || !cpuMeshes.isEmpty() || !compiledMeshes.isEmpty();
    }

    /**
     * Copies the snapshot with new CPU geometry. Geometry extraction can run less often than camera
     * updates and attach its result to the current frame.
     */
    public SceneSnapshot withCpuMeshes(List<CpuMesh> meshes, List<MaterialDef> materialTable) {
        return new SceneSnapshot(camera, chunks, environment, textures, frameIndex,
                worldLoaded, hudHidden, totalVertices, totalIndices, meshes, materialTable, images,
                compiledMeshes, geometryRevision, atlasSprites, dynamicMeshes);
    }

    /** Convenience constructor for a camera and geometry. */
    public static SceneSnapshot of(CameraParams camera, List<MeshChunk> chunks, long frameIndex) {
        return of(camera, chunks, List.of(), frameIndex);
    }

    /** Convenience constructor for a camera, host GPU geometry and extracted CPU geometry. */
    public static SceneSnapshot of(CameraParams camera, List<MeshChunk> chunks,
            List<CpuMesh> cpuMeshes, long frameIndex) {
        long vertices = 0;
        long indices = 0;
        for (MeshChunk chunk : chunks) {
            vertices += chunk.vertexCount();
            indices += chunk.indexCount();
        }
        return new SceneSnapshot(camera, chunks, Environment.DEFAULT, Map.of(),
                frameIndex, true, false, vertices, indices, cpuMeshes, List.of(), List.of());
    }
}
