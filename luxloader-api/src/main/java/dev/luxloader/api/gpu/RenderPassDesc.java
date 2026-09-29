package dev.luxloader.api.gpu;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Objects;

/**
 * Render pass description. The Vulkan backend binds attachments with dynamic rendering and handles
 * layout transitions. Passes must still declare frame graph resource access to establish ordering.
 * Callers supply output dimensions because {@link ImageHandle} is opaque and has no size.
 * @param name name
 * @param colors color attachments
 * @param depth optional depth attachment
 * @param viewportW positive viewport width, set before beginRenderPass
 * @param viewportH positive viewport height, set before beginRenderPass
 */
public record RenderPassDesc(
        String name,
        java.util.List<Attachment> colors,
        Attachment depth,
        int viewportW,
        int viewportH) {

    /** Attachment load operation (VkAttachmentLoadOp). */
    public enum LoadOp {
        /** Preserve prior frame/pass contents. */
        LOAD,
        /** Clear when the pass begins. */
        CLEAR,
        /** Undefined contents; the pass must overwrite the entire attachment. */
        DONT_CARE
    }

    /** Attachment store operation (VkAttachmentStoreOp). */
    public enum StoreOp {
        STORE,
        DONT_CARE
    }

    /**
     * Attachment.
     * @param image image
     * @param view image view
     * @param load load operation
     * @param store store operation
     * @param clearRgba RGBA clear color in [0,1], used when load is CLEAR
     * @param clearDepth depth clear value
     */
    public record Attachment(
            ImageHandle image,
            ImageViewDesc view,
            LoadOp load,
            StoreOp store,
            float[] clearRgba,
            float clearDepth) {

        public Attachment {
            Objects.requireNonNull(image, "image");
            view = view == null ? ImageViewDesc.full() : view;
            load = load == null ? LoadOp.LOAD : load;
            store = store == null ? StoreOp.STORE : store;
            clearRgba = clearRgba == null ? new float[] {0f, 0f, 0f, 1f} : clearRgba.clone();
        }

        public static Attachment color(ImageHandle image) {
            return new Attachment(image, ImageViewDesc.full(), LoadOp.LOAD, StoreOp.STORE, null, 0f);
        }

        public static Attachment colorClear(ImageHandle image, float r, float g, float b, float a) {
            return new Attachment(image, ImageViewDesc.full(), LoadOp.CLEAR, StoreOp.STORE,
                    new float[] {r, g, b, a}, 0f);
        }

        public static Attachment depth(ImageHandle image, LoadOp load) {
            return new Attachment(image, ImageViewDesc.full(), load, StoreOp.STORE, null, 0f);
        }
    }

    public RenderPassDesc {
        Objects.requireNonNull(name, "name");
        colors = colors == null ? java.util.List.of() : java.util.List.copyOf(colors);
        if (colors.isEmpty() && depth == null) {
            throw new IllegalArgumentException(tr("Render pass requires at least one attachment: ") + name);
        }
        if (viewportW < 0 || viewportH < 0) {
            throw new IllegalArgumentException(tr("viewport must not be negative"));
        }
    }

    /** Single-color-attachment pass. */
    public static RenderPassDesc to(ImageHandle color) {
        return new RenderPassDesc("post", java.util.List.of(Attachment.color(color)), null, 0, 0);
    }

    /** Color and depth pass. */
    public static RenderPassDesc to(ImageHandle color, ImageHandle depth) {
        return new RenderPassDesc("scene", java.util.List.of(Attachment.color(color)),
                Attachment.depth(depth, LoadOp.LOAD), 0, 0);
    }

    /** Pass with explicit viewport dimensions. */
    public RenderPassDesc withViewport(int w, int h) {
        return new RenderPassDesc(name, colors, depth, w, h);
    }

    public int outputWidth() {
        return viewportW;
    }

    public int outputHeight() {
        return viewportH;
    }

    /** Whether a depth attachment is used. */
    public boolean hasDepth() {
        return depth != null;
    }

    /** Number of color attachments. */
    public int colorCount() {
        return colors.size();
    }
}
