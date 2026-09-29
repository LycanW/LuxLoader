package dev.luxloader.api.scene;

import dev.luxloader.api.GpuId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ownership rule that keeps scene contributions attributable to exactly one plugin. */
class SceneOwnershipTest {

    private static final GpuId OWNER = new GpuId("dev.luxloader.example", "raster");

    @Test
    @DisplayName("Accepts The Owner Id And Its Children")
    void acceptsOwnerIdAndChildren() {
        assertTrue(SceneOwnership.isOwnedBy(OWNER.toString(), OWNER));
        assertTrue(SceneOwnership.isOwnedBy(OWNER.toString(), OWNER.child("terrain")));
        assertTrue(SceneOwnership.isOwnedBy(OWNER.toString(), OWNER.child("terrain").child("deep")));
    }

    @Test
    @DisplayName("Rejects Other Plugins And Namespaces")
    void rejectsOtherPluginsAndNamespaces() {
        assertFalse(SceneOwnership.isOwnedBy(OWNER.toString(), new GpuId("dev.luxloader.example", "raster2")),
                "A sibling pipeline must not claim the same prefix");
        assertFalse(SceneOwnership.isOwnedBy(OWNER.toString(), new GpuId("dev.luxloader.other", "raster/terrain")),
                "A different namespace is a different owner");
        assertFalse(SceneOwnership.isOwnedBy(OWNER.toString(), new GpuId("dev.luxloader.example", "other/terrain")),
                "An unrelated path is not a child");
        assertFalse(SceneOwnership.isOwnedBy("dev.luxloader.example", OWNER),
                "Incomplete owner IDs are rejected instead of matching by prefix");
        assertFalse(SceneOwnership.isOwnedBy("", OWNER), "An empty owner owns nothing");
        assertFalse(SceneOwnership.isOwnedBy(null, OWNER));
        assertFalse(SceneOwnership.isOwnedBy(OWNER.toString(), null));
    }

    @Test
    @DisplayName("Child Rule Does Not Match A Longer Sibling Name")
    void childRuleDoesNotMatchLongerSiblingName() {
        // "rasterx/terrain" starts with the owner text but is not inside the owner's path.
        GpuId sibling = new GpuId("dev.luxloader.example", "rasterx/terrain");
        assertFalse(SceneOwnership.isOwnedBy(OWNER.toString(), sibling),
                "Prefix matching must stop at a path separator");
    }
}
