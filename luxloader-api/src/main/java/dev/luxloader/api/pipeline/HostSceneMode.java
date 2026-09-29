package dev.luxloader.api.pipeline;

/**
 * How a pipeline uses the host's prepared world draws.
 *
 * <p>The host keeps preparing chunk meshes and feature submissions in both modes.
 * A pipeline choosing {@link #COMPATIBILITY} lets the host execute those draws into
 * {@link FrameGraph#COLOR} and may use that image in its own frame graph. A pipeline
 * choosing {@link #PLUGIN_SCENE} must provide the world image itself. This choice
 * is explicit so an omitted resource declaration cannot accidentally remove the
 * game's terrain, entities, or mod content.
 */
public enum HostSceneMode {
    /** Run the host's world draw as an input layer for the plugin pipeline. */
    COMPATIBILITY,
    /** The plugin supplies the world image; host preparation continues. */
    PLUGIN_SCENE
}
