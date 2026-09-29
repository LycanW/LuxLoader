package dev.luxloader.api.scene;

/**
 * Converts voxel queries to triangle meshes independently of Minecraft. Adapters translate game state
 * to VoxelSource; the mesher can be reused and tested without the game. Interleaved vertices contain
 * float3 world position, float material ID and float2 local UV. Position begins at byte zero for
 * acceleration-structure input. Hidden faces are omitted according to the source's face visibility
 * query.
 */
public final class VoxelMesher {

    /** Vertex byte stride: float3 position, float material and float2 UV. */
    public static final int VERTEX_STRIDE = 24;

    /** Floats per vertex. */
    public static final int FLOATS_PER_VERTEX = 6;

    private VoxelMesher() {
    }

    /**
     * Voxel queries used by meshing. Out-of-range cells return false so outward faces at world boundaries
     * remain visible.
     */
    public interface VoxelSource {

        /** Whether a cell contains geometry; only true cells emit faces. */
        boolean isSolid(int x, int y, int z);

        /**
         * Whether to emit this cell's face toward (dx,dy,dz), one of six unit directions with components
         * -1/0/1. Visibility depends on both cells: neighbor opacity alone cannot hide shared water faces.
         * Queried only for solid cells.
         */
        boolean shouldDrawFace(int x, int y, int z, int dx, int dy, int dz);

        /** Material value copied unchanged into the vertex material component; the source defines its meaning. */
        float materialId(int x, int y, int z);

        /**
         * Cell-local bounds in out={minX,minY,minZ,maxX,maxY,maxZ}, each in [0,1]. Defaults to a full cube and
         * is queried only for solid cells. Bounds approximate non-cube blocks such as thin plants, torches or
         * rails; alpha cutouts alone cannot correct a full-cube shape. This does not represent crossed planes
         * or slopes and is not a full model pipeline.
         */
        default void bounds(int x, int y, int z, float[] out) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 0f;
            out[3] = 1f;
            out[4] = 1f;
            out[5] = 1f;
        }
    }

    /** Meshing result. */
    public record Mesh(float[] vertices, int[] indices, int vertexCount, int triangleCount,
                       float[] boundsMin, float[] boundsMax) {

        /** Empty mesh for a region without solid cells. */
        public static Mesh empty() {
            return new Mesh(new float[0], new int[0], 0, 0,
                    new float[] {0f, 0f, 0f}, new float[] {0f, 0f, 0f});
        }

        public boolean isEmpty() {
            return triangleCount == 0;
        }
    }

    /** Six faces: outward normal and four corners in counterclockwise order viewed from outside. */
    private static final int[][] FACE_DIRS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Unit-cube corner offsets for each face, in FACE_DIRS order. */
    private static final float[][][] FACE_CORNERS = {
            // +X
            {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}},
            // -X
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
            // +Y
            {{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}},
            // -Y
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
            // +Z
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
            // -Z
            {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}}};

    /**
     * Per-face local UVs in [0,1], aligned with FACE_CORNERS. Material rectangles map them into the atlas,
     * allowing texture changes without remeshing. V grows downward as in Minecraft sprites: v=0 matches
     * getV0(), the upper edge.
     */
    private static final float[][][] FACE_UVS = {
            // +X
            {{0, 1}, {1, 1}, {1, 0}, {0, 0}},
            // -X
            {{0, 1}, {1, 1}, {1, 0}, {0, 0}},
            // +Y
            {{0, 0}, {1, 0}, {1, 1}, {0, 1}},
            // -Y
            {{0, 0}, {1, 0}, {1, 1}, {0, 1}},
            // +Z
            {{0, 1}, {1, 1}, {1, 0}, {0, 0}},
            // -Z
            {{0, 1}, {1, 1}, {1, 0}, {0, 0}}};

    /**
     * Meshes a bounded region. Supply a triangle limit on render paths to avoid unbounded work; generation
     * stops at the limit and returns the completed portion. Callers can shrink or partition the region.
     * @param source voxel queries
     * @param minX inclusive region boundary
     * @param maxTriangleCount triangle limit; nonpositive disables the limit for tests only
     */
    /**
     * Unit-cube face directions, corners and UVs for direct geometry emitters. Used as fallback when a
     * baked model fails or contains no quads. Returns internal arrays to avoid per-frame fallback
     * allocation; callers must not modify them.
     */
    public static int[][] faceDirs() {
        return FACE_DIRS;
    }

    /** See faceDirs(). */
    public static float[][][] faceCorners() {
        return FACE_CORNERS;
    }

    /** See faceDirs(). */
    public static float[][][] faceUvs() {
        return FACE_UVS;
    }

    public static Mesh mesh(VoxelSource source,                            int minX, int minY, int minZ,
                            int maxX, int maxY, int maxZ,
                            int maxTriangleCount) {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return Mesh.empty();
        }

        // Count triangles before allocating exact-sized arrays. Two scans avoid repeated growth; the second scan writes the preallocated output.
        long faces = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (!source.isSolid(x, y, z)) {
                        continue;
                    }
                    for (int[] d : FACE_DIRS) {
                        if (source.shouldDrawFace(x, y, z, d[0], d[1], d[2])) {
                            faces++;
                            if (maxTriangleCount > 0 && faces * 2L > maxTriangleCount) {
                                // Stop at the limit and generate only the counted portion.
                                y = maxY;
                                z = maxZ;
                                x = maxX;
                            }
                        }
                    }
                }
            }
        }
        int triangleCount = (int) Math.min(faces * 2L,
                maxTriangleCount > 0 ? maxTriangleCount : Long.MAX_VALUE);
        if (triangleCount == 0) {
            return Mesh.empty();
        }
        int faceCount = triangleCount / 2;

        float[] vertices = new float[faceCount * 4 * FLOATS_PER_VERTEX];
        int[] indices = new int[triangleCount * 3];
        int vCursor = 0;
        int iCursor = 0;
        int emittedFaces = 0;

        float[] boundsMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] boundsMax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

        outer:
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (!source.isSolid(x, y, z)) {
                        continue;
                    }
                    float material = source.materialId(x, y, z);
                    for (int f = 0; f < FACE_DIRS.length; f++) {
                        int[] d = FACE_DIRS[f];
                        if (!source.shouldDrawFace(x, y, z, d[0], d[1], d[2])) {
                            continue;
                        }
                        int base = vCursor / FLOATS_PER_VERTEX;
                        float[][] uv = FACE_UVS[f];
                        for (int c = 0; c < FACE_CORNERS[f].length; c++) {
                            float[] corner = FACE_CORNERS[f][c];
                            float px = x + corner[0];
                            float py = y + corner[1];
                            float pz = z + corner[2];
                            vertices[vCursor++] = px;
                            vertices[vCursor++] = py;
                            vertices[vCursor++] = pz;
                            vertices[vCursor++] = material;
                            // UVs are local texture coordinates in [0,1], not atlas coordinates.
                            vertices[vCursor++] = uv[c][0];
                            vertices[vCursor++] = uv[c][1];
                            boundsMin[0] = Math.min(boundsMin[0], px);
                            boundsMin[1] = Math.min(boundsMin[1], py);
                            boundsMin[2] = Math.min(boundsMin[2], pz);
                            boundsMax[0] = Math.max(boundsMax[0], px);
                            boundsMax[1] = Math.max(boundsMax[1], py);
                            boundsMax[2] = Math.max(boundsMax[2], pz);
                        }
                        // Split each quad into two nondegenerate triangles. Winding affects raster culling and only affects ray hits when culling is enabled.
                        indices[iCursor++] = base;
                        indices[iCursor++] = base + 1;
                        indices[iCursor++] = base + 2;
                        indices[iCursor++] = base;
                        indices[iCursor++] = base + 2;
                        indices[iCursor++] = base + 3;

                        if (++emittedFaces >= faceCount) {
                            break outer;
                        }
                    }
                }
            }
        }

        return new Mesh(vertices, indices, vCursor / FLOATS_PER_VERTEX, emittedFaces * 2,
                boundsMin, boundsMax);
    }
}
