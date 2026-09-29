package dev.luxloader.api.pipeline;

import dev.luxloader.api.gpu.AccelStructDesc;
import dev.luxloader.api.gpu.BufferDesc;
import dev.luxloader.api.gpu.ImageDesc;

import java.util.List;
import java.util.Objects;

/**
 * Pipeline resource requirements returned during initialization. Used for memory-budget preflight,
 * reuse of compatible resources during hot switching and diagnostic estimates.
 * @param images required images
 * @param buffers required buffers
 * @param accelStructs required acceleration structures
 * @param scopedRecreateOnResize whether to recreate the scope on resize (typically true for history
 * resources, false for constant buffers)
 * @param notes diagnostic notes
 */
public record ResourceRequest(
        List<ImageDesc> images,
        List<BufferDesc> buffers,
        List<AccelStructDesc> accelStructs,
        boolean scopedRecreateOnResize,
        List<String> notes) {

    /** No resource requirements. */
    public static final ResourceRequest EMPTY = new ResourceRequest(List.of(), List.of(), List.of(), false, List.of());

    public ResourceRequest {
        images = images == null ? List.of() : List.copyOf(images);
        buffers = buffers == null ? List.of() : List.copyOf(buffers);
        accelStructs = accelStructs == null ? List.of() : List.copyOf(accelStructs);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Estimate GPU memory in bytes: textures use a conservative 4 bytes/pixel and acceleration structures
     * use declared capacity.
     */
    public long estimatedBytes() {
        long total = 0;
        for (ImageDesc d : images) {
            int bpp = d.format().bytesPerPixel();
            if (bpp <= 0) {
                bpp = 4;
            }
            total += (long) d.width() * d.height() * bpp * Math.max(1, d.layers());
        }
        for (BufferDesc b : buffers) {
            total += b.size();
        }
        for (AccelStructDesc a : accelStructs) {
            // Rough estimate: 64 bytes per triangle for BVH/index storage and 64 bytes per instance.
            total += (long) a.triangleCount() * 64L + (long) a.instanceCount() * 64L;
        }
        return total;
    }

    /** Readable memory estimate. */
    public String estimatedHuman() {
        long bytes = estimatedBytes();
        if (bytes >= 1L << 30) {
            return String.format(java.util.Locale.ROOT, "%.2f GiB", bytes / (double) (1L << 30));
        }
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (double) (1L << 20));
    }

    /** Whether ray tracing resources are declared. */
    public boolean usesRayTracing() {
        return !accelStructs.isEmpty();
    }

    public ResourceRequest merge(ResourceRequest other) {
        if (other == null || other == EMPTY) {
            return this;
        }
        List<ImageDesc> imgs = new java.util.ArrayList<>(images);
        imgs.addAll(other.images);
        List<BufferDesc> bufs = new java.util.ArrayList<>(buffers);
        bufs.addAll(other.buffers);
        List<AccelStructDesc> accels = new java.util.ArrayList<>(accelStructs);
        accels.addAll(other.accelStructs);
        List<String> n = new java.util.ArrayList<>(notes);
        n.addAll(other.notes);
        return new ResourceRequest(imgs, bufs, accels,
                scopedRecreateOnResize || other.scopedRecreateOnResize, n);
    }

    /** Resource requirements builder. */
    public static final class Builder {
        private final List<ImageDesc> images = new java.util.ArrayList<>();
        private final List<BufferDesc> buffers = new java.util.ArrayList<>();
        private final List<AccelStructDesc> accel = new java.util.ArrayList<>();
        private boolean recreateOnResize = true;
        private final List<String> notes = new java.util.ArrayList<>();

        public Builder image(ImageDesc desc) {
            images.add(Objects.requireNonNull(desc, "desc"));
            return this;
        }

        public Builder buffer(BufferDesc desc) {
            buffers.add(Objects.requireNonNull(desc, "desc"));
            return this;
        }

        public Builder accelStruct(AccelStructDesc desc) {
            accel.add(Objects.requireNonNull(desc, "desc"));
            return this;
        }

        public Builder keepOnResize() {
            this.recreateOnResize = false;
            return this;
        }

        public Builder note(String note) {
            notes.add(note);
            return this;
        }

        public ResourceRequest build() {
            return new ResourceRequest(images, buffers, accel, recreateOnResize, notes);
        }
    }
}
