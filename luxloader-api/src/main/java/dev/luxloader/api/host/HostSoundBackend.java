package dev.luxloader.api.host;

import dev.luxloader.api.presentation.PresentationService;
import dev.luxloader.api.presentation.SoundAsset;

/**
 * Optional host audio boundary. Core calls start/update/activity/pause at the client safe point.
 * Cancellation is safe from any thread: invalidate late starts immediately and schedule host cleanup
 * when necessary. The host retains responsibility for decoding, audio threads and device timing.
 */
public interface HostSoundBackend extends AutoCloseable {
    HostSoundBackend NONE = new HostSoundBackend() {
        public Capabilities capabilities() { return Capabilities.NONE; }
        public Voice start(PresentationService.Source source, SoundAsset asset,
                           PresentationService.Category category, boolean looping,
                           PresentationService.Parameters parameters) { return null; }
    };

    record Capabilities(boolean hostEvents, boolean hostOgg, boolean preparedOgg,
                        boolean positionUpdates, boolean volumeUpdates, boolean pause,
                        boolean hostObservation) {
        public static final Capabilities NONE = new Capabilities(false, false, false, false, false, false, false);
        public boolean supports(SoundAsset asset) {
            return asset instanceof SoundAsset.HostEvent ? hostEvents
                    : asset instanceof SoundAsset.HostOgg ? hostOgg : asset instanceof SoundAsset.PreparedOgg && preparedOgg;
        }
    }

    Capabilities capabilities();
    Voice start(PresentationService.Source source, SoundAsset asset, PresentationService.Category category,
                boolean looping, PresentationService.Parameters parameters);
    /**
     * Thread-safe demand publication. Reject generations older than the last accepted request so a
     * delayed enable cannot resurrect a listener after the last subscription closes. Actual listener
     * attachment/removal belongs to the host client thread.
     */
    default void observe(long generation, boolean enabled, java.util.function.Consumer<PresentationService.HostSound> sink) { }
    @Override default void close() { }

    interface Voice {
        PresentationService.SoundStatus startResult();
        /** Optional later decode failure; still no device audibility claim. */
        default PresentationService.SoundStatus status() { return startResult(); }
        /** Host bookkeeping, not device audibility. */
        boolean isActive();
        void update(PresentationService.Parameters parameters);
        void setPaused(boolean paused);
        /** Idempotent; guards late consumption before queuing native cleanup. */
        void cancel();
    }
}
