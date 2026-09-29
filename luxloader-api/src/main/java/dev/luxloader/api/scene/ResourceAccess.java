package dev.luxloader.api.scene;

import java.io.IOException;
import java.util.Optional;

/** Host resource-pack view. Paths are opaque to the kernel; decoding belongs to plugins. */
public interface ResourceAccess {
    ResourceAccess EMPTY = new ResourceAccess() {
        @Override public long revision() { return 0; }
        @Override public Optional<byte[]> read(String namespace, String path) { return Optional.empty(); }
    };

    /** Changes when the host replaces or reloads its resource stack. */
    long revision();

    /** Legacy implementations remain ready and use their existing revision as the generation. */
    default dev.luxloader.api.resource.ResourceState state() {
        return new dev.luxloader.api.resource.ResourceState(revision(),
                dev.luxloader.api.resource.ResourceState.Phase.READY);
    }

    /**
     * Acquires a resource stream on the host thread. Ownership transfers to the caller, which may
     * read/close the stream on its CPU worker. No Minecraft object escapes through this entry point.
     * Implementations must resolve the current pack winner at acquisition time. A generation change
     * may invalidate the stream; consumers must reject that generation even if reading succeeds.
     * The compatibility default retains the old synchronous read behavior.
     */
    default Optional<java.io.InputStream> open(dev.luxloader.api.resource.ResourceKey key) throws IOException {
        return read(key.namespace(), key.path()).map(java.io.ByteArrayInputStream::new);
    }

    /** Frame currently published in the atlas; does not change resource revision. */
    default TextureAnimation animation(TextureRef texture) { return TextureAnimation.STATIC; }

    /** Highest-priority resource contents; missing is distinct from an I/O failure.
     * Call during loading/reload, cache by revision, and do not read per pixel or per draw.
     */
    Optional<byte[]> read(String namespace, String path) throws IOException;
}
