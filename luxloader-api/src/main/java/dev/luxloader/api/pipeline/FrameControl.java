package dev.luxloader.api.pipeline;

import dev.luxloader.api.GpuId;

/**
 * Per-frame control separate from pass rendering: execution, resolution and presentation timing.
 * @param run whether the pipeline runs; false leaves game rendering untouched
 * @param requestedRenderScale requested scale for dynamic resolution; zero preserves the current scale
 * @param requestReset reset temporal history
 * @param requestResize rebuild swapchain-sized resources
 * @param requestReload reload configuration and plugins
 * @param requestPresentControl take over pacing for frame generation or async reprojection
 * @param reason diagnostic reason
 */
public record FrameControl(
        boolean run,
        float requestedRenderScale,
        boolean requestReset,
        boolean requestResize,
        boolean requestReload,
        boolean requestPresentControl,
        String reason) {

    /** Continue without adjustments. */
    public static FrameControl normal() {
        return new FrameControl(true, 0f, false, false, false, false, "");
    }

    /** Skip intervention this frame, for example in menus or loading screens. */
    public static FrameControl skip(String why) {
        return new FrameControl(false, 0f, false, false, false, false, why == null ? "" : why);
    }

    /** Request history reset. */
    public static FrameControl reset(String why) {
        return new FrameControl(true, 0f, true, false, false, false, why == null ? "" : why);
    }

    /**
     * Request dynamic resolution.
     * @param scale render scale in (0, 1]
     */
    public static FrameControl renderScale(float scale) {
        return new FrameControl(true, clamp(scale), false, false, false, false, "dynamic-resolution");
    }

    /** Own presentation pacing for frame generation or asynchronous reprojection. */
    public static FrameControl presentControl() {
        return new FrameControl(true, 0f, false, false, false, true, "present-control");
    }

    private static float clamp(float scale) {
        if (scale <= 0f) {
            return 0f;
        }
        return Math.min(scale, 1.0f);
    }

    public FrameControl {
        requestedRenderScale = clamp(requestedRenderScale);
        reason = reason == null ? "" : reason;
    }

    /** Whether a render-resolution change was requested. */
    public boolean changesRenderScale() {
        return requestedRenderScale > 0f;
    }

    /** Whether any requested action requires reconstruction. */
    public boolean needsRebuild() {
        return requestResize || requestReload;
    }

    /** Copy with an additional reset request. */
    public FrameControl withReset() {
        return new FrameControl(run, requestedRenderScale, true, requestResize, requestReload,
                requestPresentControl, reason);
    }

    /** Copy with a specified render scale. */
    public FrameControl withRenderScale(float scale) {
        return new FrameControl(run, clamp(scale), requestReset, requestResize, requestReload,
                requestPresentControl, reason);
    }

    /** Copy with an explanatory reason. */
    public FrameControl because(String why) {
        return new FrameControl(run, requestedRenderScale, requestReset, requestResize, requestReload,
                requestPresentControl, why == null ? "" : why);
    }

    public String describe() {
        if (!run) {
            return "skip(" + reason + ")";
        }
        StringBuilder sb = new StringBuilder("run");
        if (changesRenderScale()) {
            sb.append(" scale=").append(String.format(java.util.Locale.ROOT, "%.3f", requestedRenderScale));
        }
        if (requestReset) {
            sb.append(" reset");
        }
        if (requestResize) {
            sb.append(" resize");
        }
        if (requestReload) {
            sb.append(" reload");
        }
        if (requestPresentControl) {
            sb.append(" present-control");
        }
        if (!reason.isEmpty()) {
            sb.append(" (").append(reason).append(')');
        }
        return sb.toString();
    }

    /** Construct a pass identifier for frame graphs. */
    static GpuId id(GpuId parent, String child) {
        return parent.child(child);
    }
}
