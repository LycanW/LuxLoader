package dev.luxloader.api.scene;

/**
 * A revisioned stream of compiled geometry. Plugins keep their own cursor and
 * request changes when they are ready; the host never guesses which frame a
 * plugin consumed. A stale cursor receives a reset plus the current contents.
 */
@FunctionalInterface
public interface SceneGeometryFeed {
    SceneGeometryDelta changesSince(long revision);
}
