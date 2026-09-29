package dev.luxloader.api.frame;

import dev.luxloader.api.gpu.ImageHandle;

/**
 * GPU images available to a pipeline for this frame. Null means unavailable and requires fallback.
 * Scene color (HDR linear or LDR per FrameData.hdr), depth, motion vectors and masks use render
 * resolution; output uses display resolution. Exposure may be a 1x1 image or absent for manual/backend
 * exposure. Reactive and transparency masks identify regions needing reduced temporal reuse. UI and
 * history are optional separate layers; backends may keep their own history.
 */
public record FrameTextures(
        ImageHandle color,
        ImageHandle depth,
        ImageHandle motionVectors,
        ImageHandle exposure,
        ImageHandle reactiveMask,
        ImageHandle transparencyMask,
        ImageHandle output,
        ImageHandle ui,
        ImageHandle history) {

    /** Empty texture set for pipelines that do not read host frame textures. */
    public static final FrameTextures EMPTY = new FrameTextures(
            null, null, null, null, null, null, null, null, null);

    public boolean hasColor() {
        return color != null && !color.isNull();
    }

    public boolean hasDepth() {
        return depth != null && !depth.isNull();
    }

    public boolean hasMotionVectors() {
        return motionVectors != null && !motionVectors.isNull();
    }

    public boolean hasExposure() {
        return exposure != null && !exposure.isNull();
    }

    public boolean hasOutput() {
        return output != null && !output.isNull();
    }

    /** Temporal upscaling requires color, depth, motion vectors and output. */
    public boolean canTemporalUpscale() {
        return hasColor() && hasDepth() && hasMotionVectors() && hasOutput();
    }

    /** Spatial upscaling requires color and output. */
    public boolean canSpatialUpscale() {
        return hasColor() && hasOutput();
    }

    /** Copy with a replacement output, used for frame generation between display-resolution images. */
    public FrameTextures withOutput(ImageHandle newOutput) {
        return new FrameTextures(color, depth, motionVectors, exposure, reactiveMask,
                transparencyMask, newOutput, ui, history);
    }

    /** Copy with a replacement history buffer. */
    public FrameTextures withHistory(ImageHandle newHistory) {
        return new FrameTextures(color, depth, motionVectors, exposure, reactiveMask,
                transparencyMask, output, ui, newHistory);
    }

    /** One-line diagnostic summary. */
    public String describe() {
        return "color=" + name(color) + " depth=" + name(depth) + " motion=" + name(motionVectors)
                + " exposure=" + name(exposure) + " output=" + name(output);
    }

    private static String name(ImageHandle h) {
        return h == null || h.isNull() ? "-" : h.label();
    }
}
