package dev.luxloader.api.scene;

import java.util.List;

/** Optional mod geometry bridge, invoked on the render thread before the frame plan.
 * Return final posed world geometry, including off-camera geometry needed by secondary rays.
 * Mesh bytes must be immutable; GPU images are borrowed and must survive frame completion.
 * Custom shader effects cannot be inferred from their final color and need explicit adapters.
 */
@FunctionalInterface
public interface SceneContributor {
    List<DynamicSceneMesh> contribute(SceneSnapshot hostScene);
}
