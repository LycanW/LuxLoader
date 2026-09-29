package dev.luxloader.api.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link VoxelMesher} face culling without a GPU or game instance. Missing culling inflates mesh
 * and BVH costs; excessive culling removes visible surfaces. Verify geometry counts and bounds
 * directly.
 */
class VoxelMesherTest {

    /** A set represents solid cells; opaque cells are solid by default. */
    private static final class SetSource implements VoxelMesher.VoxelSource {
        private final Set<String> solid = new HashSet<>();
        private final Set<String> transparent = new HashSet<>();

        SetSource block(int x, int y, int z) {
            solid.add(key(x, y, z));
            return this;
        }

        SetSource glass(int x, int y, int z) {
            solid.add(key(x, y, z));
            transparent.add(key(x, y, z));
            return this;
        }

        private static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return solid.contains(key(x, y, z));
        }

        @Override
        public boolean shouldDrawFace(int x, int y, int z, int dx, int dy, int dz) {
            // Equivalent to the old isOpaque contract: suppress a face when its neighbor is solid and opaque.
            return !(solid.contains(key(x + dx, y + dy, z + dz))
                    && !transparent.contains(key(x + dx, y + dy, z + dz)));
        }

        @Override
        public float materialId(int x, int y, int z) {
            return (float) (x * 31 + y * 7 + z);
        }
    }

    @Test
    @DisplayName("Same Material Faces Are Culled By Direction")
    void sameMaterialFacesAreCulledByDirection() {
        // Visibility depends on both cells. A neighbor-only opacity query cannot suppress internal faces between water cells.
        VoxelMesher.VoxelSource water = new VoxelMesher.VoxelSource() {
            @Override
            public boolean isSolid(int x, int y, int z) {
                return (x == 0 || x == 1) && y == 0 && z == 0;
            }

            @Override
            public boolean shouldDrawFace(int x, int y, int z, int dx, int dy, int dz) {
                int nx = x + dx;
                boolean neighbourIsWater = (nx == 0 || nx == 1) && y + dy == 0 && z + dz == 0;
                return !neighbourIsWater;
            }

            @Override
            public float materialId(int x, int y, int z) {
                return 1f;
            }
        };
        var mesh = VoxelMesher.mesh(water, 0, 0, 0, 1, 0, 0, 0);
        assertEquals(20, mesh.triangleCount(), "Two adjacent blocks: 12 faces minus 2 internal faces = 10 faces = 20 triangles");
        assertEquals(40, mesh.vertexCount(), "10 faces with 4 vertices each");
    }

    @Test
    @DisplayName("Single Block Emits All Six Faces")
    void singleBlockEmitsAllSixFaces() {
        var source = new SetSource().block(0, 0, 0);
        var mesh = VoxelMesher.mesh(source, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(12, mesh.triangleCount(), "An isolated block has 6 faces with 2 triangles each");
        assertEquals(24, mesh.vertexCount(), "4 vertices per face");
        assertEquals(12 * 3, mesh.indices().length,
                "Index count is three times triangle count (12 * 3 = 36), not vertex count");
        assertFalse(mesh.isEmpty());

        // Bounds must match the unit cube exactly.
        assertEquals(0f, mesh.boundsMin()[0], 1e-6f);
        assertEquals(0f, mesh.boundsMin()[1], 1e-6f);
        assertEquals(0f, mesh.boundsMin()[2], 1e-6f);
        assertEquals(1f, mesh.boundsMax()[0], 1e-6f);
        assertEquals(1f, mesh.boundsMax()[1], 1e-6f);
        assertEquals(1f, mesh.boundsMax()[2], 1e-6f);

        // Vertices must contain the material ID alongside their position and UV data.
        assertEquals(VoxelMesher.VERTEX_STRIDE, VoxelMesher.FLOATS_PER_VERTEX * 4);
        assertEquals(0f, mesh.vertices()[3], 1e-6f, "The fourth float stores material, not position");
    }

    @Test
    @DisplayName("Adjacent Blocks Cull The Shared Face")
    void adjacentBlocksCullTheSharedFace() {
        var source = new SetSource().block(0, 0, 0).block(1, 0, 0);
        var mesh = VoxelMesher.mesh(source, 0, 0, 0, 1, 0, 0, 0);

        assertEquals(20, mesh.triangleCount(), "Two blocks: 12 faces minus 2 internal faces = 20 triangles");
    }

    @Test
    @DisplayName("Solid Cube Emits Only The Shell")
    void solidCubeEmitsOnlyTheShell() {
        var source = new SetSource();
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    source.block(x, y, z);
                }
            }
        }
        var mesh = VoxelMesher.mesh(source, 0, 0, 0, 1, 1, 1, 0);

        // Without culling there would be 8 * 6 = 48 faces; the shell has 6 * 4 = 24 faces.
        assertEquals(48, mesh.triangleCount(), "24 shell faces with 2 triangles each");
        assertEquals(2f, mesh.boundsMax()[0], 1e-6f);
    }

    @Test
    @DisplayName("Out Of Bounds Neighbour Is Not Opaque")
    void outOfBoundsNeighbourIsNotOpaque() {
        var source = new SetSource().block(0, 0, 0);
        // Only this cell is inside the range, so all six neighbors are outside.
        var mesh = VoxelMesher.mesh(source, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(12, mesh.triangleCount(),
                "Out-of-range cells must count as absent neighbors to preserve boundary faces");

        // If solid neighbors cover every face, the cell must emit no geometry.
        var walled = new SetSource();
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    walled.block(x, y, z);
                }
            }
        }
        var buried = VoxelMesher.mesh(walled, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(buried.isEmpty(), "A fully enclosed block must emit no faces");
    }

    @Test
    @DisplayName("Transparent Block Does Not Cull Neighbours")
    void transparentBlockDoesNotCullNeighbours() {
        var source = new SetSource().block(0, 0, 0).glass(1, 0, 0);
        var mesh = VoxelMesher.mesh(source, 0, 0, 0, 1, 0, 0, 0);

        // Glass emits geometry but does not occlude the neighboring solid face.
        assertEquals(12 + 10, mesh.triangleCount(),
                "The solid block emits all 6 faces; glass loses its face toward the solid block, "
                        + "giving 12 + 10 = 22 triangles");
    }

    @Test
    @DisplayName("Triangle Cap Is Honoured")
    void triangleCapIsHonoured() {
        var source = new SetSource();
        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < 8; z++) {
                for (int x = 0; x < 8; x++) {
                    source.block(x, y, z);
                }
            }
        }
        var capped = VoxelMesher.mesh(source, 0, 0, 0, 7, 7, 7, 100);
        assertTrue(capped.triangleCount() <= 100,
                "Actual count " + capped.triangleCount() + " exceeds the limit of 100");
        assertTrue(capped.triangleCount() > 0);

        // Without a budget, the shell has 6 * 8 * 8 = 384 faces, or 768 triangles.
        var full = VoxelMesher.mesh(source, 0, 0, 0, 7, 7, 7, 0);
        assertEquals(768, full.triangleCount(),
                "An 8-cubed solid volume must have 6 * 64 shell faces");
    }

    @Test
    @DisplayName("Empty And Degenerate Ranges Are Safe")
    void emptyAndDegenerateRangesAreSafe() {
        var source = new SetSource();
        assertTrue(VoxelMesher.mesh(source, 0, 0, 0, 4, 4, 4, 0).isEmpty());
        // max < min represents an empty world range, such as a menu or loading screen.
        assertTrue(VoxelMesher.mesh(source, 4, 4, 4, 0, 0, 0, 0).isEmpty());
        assertEquals(0, VoxelMesher.Mesh.empty().triangleCount());
    }
}
