package dev.luxloader.api.presentation;

import dev.luxloader.api.resource.ResourceKey;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Explicit sound addressing. No variant accepts a filesystem path or installs a resource pack. */
public sealed interface SoundAsset {
    /** An event resolved by the host sound registry and selected resource-pack stack. */
    record HostEvent(ResourceKey id) implements SoundAsset {
        public HostEvent { Objects.requireNonNull(id, "id"); }
    }

    /** An OGG in the host resource stack, addressed as namespace:sounds/path.ogg. */
    record HostOgg(ResourceKey id, boolean streaming) implements SoundAsset {
        public HostOgg {
            Objects.requireNonNull(id, "id");
            if (!id.path().startsWith("sounds/") || !id.path().endsWith(".ogg")
                    || id.path().length() <= "sounds/.ogg".length()) {
                throw new IllegalArgumentException("Host OGG assets require a sounds/*.ogg resource key");
            }
        }
    }

    /**
     * Plugin-prepared encoded OGG bytes, independent of the host pack stack. The plugin owns lookup,
     * preparation and its revision; the host owns decoding and playback. Construction copies the
     * remaining bytes. An OggS header is admission validation, not proof of a decodable Vorbis stream.
     * Prepare assets away from client/render callbacks. The service also bounds retained asset bytes.
     */
    final class PreparedOgg implements SoundAsset {
        public static final int MAX_ENCODED_BYTES = 16 * 1024 * 1024;
        private final ResourceKey id;
        private final long revision;
        private final byte[] encoded;

        public PreparedOgg(ResourceKey id, long revision, ByteBuffer encoded) {
            this.id = Objects.requireNonNull(id, "id");
            if (revision < 0) throw new IllegalArgumentException("Asset revision must not be negative");
            this.revision = revision;
            var input = Objects.requireNonNull(encoded, "encoded").duplicate();
            if (input.remaining() < 4 || input.remaining() > MAX_ENCODED_BYTES) {
                throw new IllegalArgumentException("Encoded OGG must contain 4 bytes to 16 MiB");
            }
            this.encoded = new byte[input.remaining()];
            input.get(this.encoded);
            if (this.encoded[0] != 'O' || this.encoded[1] != 'g'
                    || this.encoded[2] != 'g' || this.encoded[3] != 'S') {
                throw new IllegalArgumentException("Encoded OGG requires the OggS container header");
            }
        }

        public ResourceKey id() { return id; }
        public long revision() { return revision; }
        public int encodedBytes() { return encoded.length; }
        public ByteBuffer encoded() { return ByteBuffer.wrap(encoded).asReadOnlyBuffer(); }
    }
}
