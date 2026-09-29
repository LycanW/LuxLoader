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

    /** Frame currently published in the atlas; does not change resource revision. */
    default TextureAnimation animation(TextureRef texture) { return TextureAnimation.STATIC; }

    /** Highest-priority resource contents; missing is distinct from an I/O failure.
     * Call during loading/reload, cache by revision, and do not read per pixel or per draw.
     */
    Optional<byte[]> read(String namespace, String path) throws IOException;
}
