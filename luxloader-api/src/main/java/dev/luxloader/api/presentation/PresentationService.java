package dev.luxloader.api.presentation;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.event.EventValue;
import dev.luxloader.api.host.HostSoundBackend;
import dev.luxloader.api.scene.DynamicSceneMesh;
import dev.luxloader.api.scene.SceneSnapshot;
import dev.luxloader.api.state.ClientStateSnapshot.LogicalTime;
import dev.luxloader.api.state.ClientStateSnapshot.WorldSession;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Bounded, plugin-instance-owned world presentations. Registration stores metadata and produces no
 * output. Explicit starts are processed after state/event delivery at the client safe point. Calls
 * to start/update/pause/cancel are thread-safe; they never run plugin render code or native audio.
 * A render boundary invokes admitted visual contributors through the existing dynamic scene path.
 * Disable, pipeline replacement, resource/world change and owner release cancel existing instances.
 */
public interface PresentationService {
    PresentationService UNAVAILABLE = new PresentationService() {
        public Registration register(Definition definition) {
            throw new UnsupportedOperationException("World presentations are unavailable");
        }
        public Capabilities capabilities() { return Capabilities.UNAVAILABLE; }
        public Subscription observeHostSounds(Consumer<SoundBatch> listener) {
            throw new UnsupportedOperationException("Host sound observation is unavailable");
        }
    };

    Registration register(Definition definition);
    Capabilities capabilities();

    /**
     * Observes host play notifications, not device output. All LuxLoader-managed sounds are excluded
     * before capture, including sounds from other plugins, so output cannot feed this trigger again.
     * Listeners run only at a later client safe point, never on an audio/IO thread. Overflow delivers
     * an empty resynchronization batch with a dropped count; it does not replay partial history.
     */
    Subscription observeHostSounds(Consumer<SoundBatch> listener);

    record Capabilities(boolean pipelineActive, Set<GpuId> visualTypes, HostSoundBackend.Capabilities sound) {
        public static final Capabilities UNAVAILABLE = new Capabilities(false, Set.of(), HostSoundBackend.Capabilities.NONE);
        public Capabilities {
            visualTypes = Set.copyOf(visualTypes);
            Objects.requireNonNull(sound, "sound");
        }
    }

    /** Geometry/style is plugin code; returned immutable bytes and borrowed images must survive GPU completion. */
    @FunctionalInterface
    interface Visual {
        List<DynamicSceneMesh> contribute(View instance, SceneSnapshot hostScene);
    }

    enum Category { MASTER, MUSIC, RECORDS, WEATHER, BLOCKS, HOSTILE, NEUTRAL, PLAYERS, AMBIENT, VOICE }

    record Definition(GpuId id, Visual visual, SoundAsset sound, Category category, boolean looping) {
        public Definition {
            Objects.requireNonNull(id, "id");
            category = Objects.requireNonNull(category, "category");
            if (visual == null && sound == null) throw new IllegalArgumentException("A presentation requires an output");
        }
    }

    /** Immutable world position, strength and audio controls. Custom parameters are bounded API values. */
    record Parameters(double x, double y, double z, float intensity, float volume, float pitch,
                      boolean relativeSound, EventValue.ObjectValue custom) {
        public Parameters {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(intensity) || intensity < 0
                    || !Float.isFinite(volume) || volume < 0 || volume > 1
                    || !Float.isFinite(pitch) || pitch < 0.5f || pitch > 2f) {
                throw new IllegalArgumentException("Presentation parameters must be finite and within audio ranges");
            }
            Objects.requireNonNull(custom, "custom");
        }
        public static Parameters at(double x, double y, double z) {
            return new Parameters(x, y, z, 1, 1, 1, false, EventValue.ObjectValue.empty());
        }
    }

    /**
     * Start and lifetime use shared logical elapsed nanoseconds, not wall/day/render time. A missed entire
     * lifetime expires without starting. There is no audio seek, sample clock or sample synchronization.
     * World identity is supplied from an observed snapshot and validated again before output starts.
     */
    record Request(WorldSession world, long targetNanos, long durationNanos, Parameters parameters) {
        public Request {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(parameters, "parameters");
            if (targetNanos < 0 || durationNanos <= 0) throw new IllegalArgumentException("Invalid presentation lifetime");
        }
    }

    /** Provenance is assigned by the service, never supplied by the plugin. IDs are instance-scoped. */
    record Source(String pluginId, long ownerInstance, long instanceId, GpuId definitionId) { }
    enum State { QUEUED, RUNNING, PAUSED, COMPLETED, CANCELLED, REJECTED }
    enum Reason { NONE, EXPIRED, REQUESTED, OWNER_RELEASED, REGISTRATION_CLOSED, PIPELINE_STOPPED,
        WORLD_CHANGED, NO_WORLD, RESOURCE_CHANGED, STATE_UNAVAILABLE, BACKPRESSURE, SERVICE_CLOSED }
    enum VisualStatus { NONE, PENDING, UNSUPPORTED, AVAILABLE, CONTRIBUTED, FAILED }
    /** Host request result/activity only. None of these values asserts audible samples reached a device. */
    enum SoundStatus { NONE, PENDING, UNSUPPORTED, HOST_STARTED, HOST_STARTED_SILENTLY,
        HOST_NOT_STARTED, HOST_INACTIVE, UNKNOWN, FAILED, STOP_REQUESTED }

    record View(Source source, WorldSession world, LogicalTime time, long targetNanos, long elapsedNanos,
                long durationNanos, long resourceGeneration, Parameters parameters) { }
    record Snapshot(State state, Reason reason, VisualStatus visual, SoundStatus sound, View view) {
        public boolean terminal() { return state == State.COMPLETED || state == State.CANCELLED || state == State.REJECTED; }
    }

    interface Registration extends AutoCloseable {
        GpuId id();
        Handle start(Request request);
        boolean isClosed();
        @Override void close();
    }

    interface Handle extends AutoCloseable {
        Snapshot snapshot();
        /** Coalesces parameters until the next safe point. False after termination. */
        boolean update(Parameters parameters);
        /** Freezes this instance's progress; host world pause independently freezes logical time. */
        boolean setPaused(boolean paused);
        /** Immediately invalidates visual admission and requests owned audio cancellation, idempotently. */
        @Override void close();
    }

    record HostSound(dev.luxloader.api.resource.ResourceKey eventId,
                     dev.luxloader.api.resource.ResourceKey resolvedAsset, Parameters parameters) { }
    record SoundBatch(WorldSession world, LogicalTime time, List<HostSound> sounds, boolean resynchronize, long dropped) {
        public SoundBatch { sounds = List.copyOf(sounds); }
    }
    interface Subscription extends AutoCloseable {
        boolean isActive();
        @Override void close();
    }
}
