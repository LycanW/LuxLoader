package dev.luxloader.api.scene;

/**
 * A revisioned stream of compiled geometry. Plugins keep their own cursor and
 * request changes when they are ready; the host never guesses which frame a
 * plugin consumed. A stale cursor receives a reset plus the current contents.
 */
@FunctionalInterface
public interface SceneGeometryFeed {
    SceneGeometryDelta changesSince(long revision);

    /**
     * Resource generation of the complete current feed, when the host can prove it.
     * A negative value means resource rebuild is incomplete. Legacy feeds return empty.
     */
    default java.util.OptionalLong resourceGeneration() { return java.util.OptionalLong.empty(); }
}
