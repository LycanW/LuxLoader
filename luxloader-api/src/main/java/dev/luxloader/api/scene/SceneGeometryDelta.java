package dev.luxloader.api.scene;

import java.util.List;
import java.util.Set;

/**
 * Changes to accepted scene geometry after a consumer's last revision.
 * A reset means the consumer must discard its old instances before applying
 * {@code upserts}; otherwise unchanged instances remain valid.
 */
public record SceneGeometryDelta(long fromRevision, long toRevision, boolean reset,
                                 List<CompiledSceneMesh> upserts, Set<String> removals) {
    public SceneGeometryDelta {
        if (fromRevision < 0 || toRevision < fromRevision) {
            throw new IllegalArgumentException("Invalid scene revision range");
        }
        upserts = upserts == null ? List.of() : List.copyOf(upserts);
        removals = removals == null ? Set.of() : Set.copyOf(removals);
        for (CompiledSceneMesh mesh : upserts) {
            if (removals.contains(mesh.id())) {
                throw new IllegalArgumentException("Mesh is both added and removed: " + mesh.id());
            }
        }
    }

    public boolean unchanged() {
        return !reset && upserts.isEmpty() && removals.isEmpty();
    }
}
