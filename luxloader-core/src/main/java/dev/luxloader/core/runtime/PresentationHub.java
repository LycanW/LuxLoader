package dev.luxloader.core.runtime;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.event.EventValue;
import dev.luxloader.api.host.HostSoundBackend;
import dev.luxloader.api.pipeline.RenderPipeline;
import dev.luxloader.api.presentation.PresentationService;
import dev.luxloader.api.presentation.SoundAsset;
import dev.luxloader.api.resource.ResourceState;
import dev.luxloader.api.scene.DynamicSceneMesh;
import dev.luxloader.api.scene.SceneOwnership;
import dev.luxloader.api.scene.SceneSnapshot;
import dev.luxloader.api.state.ClientStateSnapshot;

import java.util.*;
import java.util.function.Consumer;

import static dev.luxloader.api.presentation.PresentationService.*;

/** CPU-only scheduling and ownership; GPU resources remain with the contributing plugin/pipeline. */
final class PresentationHub implements AutoCloseable {
    static final int MAX_REGISTRATIONS = 128;
    static final int MAX_HANDLES = 256;
    static final int MAX_HANDLES_PER_OWNER = 64;
    static final int MAX_SOUND_OBSERVERS = 64;
    static final int MAX_SOUND_QUEUE = 128;
    static final int MAX_MESHES_PER_INSTANCE = 64;
    static final long MAX_ASSET_BYTES = 128L * 1024 * 1024;

    private final Object lock = new Object();
    private final Consumer<Throwable> failure;
    private final Map<GpuId, Registered> registrations = new LinkedHashMap<>();
    private final Map<Long, Instance> instances = new LinkedHashMap<>();
    private final Set<Observed> observers = new LinkedHashSet<>();
    private final ArrayDeque<PendingSound> soundQueue = new ArrayDeque<>();
    private HostSoundBackend backend = HostSoundBackend.NONE;
    private Capabilities capabilities = Capabilities.UNAVAILABLE;
    private ClientStateSnapshot.LogicalTime time = new ClientStateSnapshot.LogicalTime(0, 0, false);
    private long worldGeneration = -1;
    private long resourceGeneration = -1;
    private long nextId, assetBytes, rejected, peakHandles, starts, visualCalls, soundDropped, observationGeneration;
    private boolean closed, soundGap;

    PresentationHub(Consumer<Throwable> failure) { this.failure = Objects.requireNonNull(failure); }

    void attach(HostSoundBackend supplied) {
        Objects.requireNonNull(supplied);
        var sound = supplied.capabilities();
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Presentation service is closed");
            if (backend != HostSoundBackend.NONE && backend != supplied) {
                throw new IllegalStateException("Host sound backend is already attached");
            }
            backend = supplied;
            capabilities = new Capabilities(capabilities.pipelineActive(), capabilities.visualTypes(), sound);
        }
    }

    PresentationService service(long owner, String pluginId) {
        return new PresentationService() {
            public Registration register(Definition definition) { return registerOwned(owner, pluginId, definition); }
            public Capabilities capabilities() { synchronized (lock) { return capabilities; } }
            public Subscription observeHostSounds(Consumer<SoundBatch> listener) {
                synchronized (lock) {
                    requireOpen();
                    if (observers.size() >= MAX_SOUND_OBSERVERS) throw new IllegalStateException("Host sound observer limit reached");
                    var observer = new Observed(owner, Objects.requireNonNull(listener));
                    observers.add(observer);
                    return observer;
                }
            }
        };
    }

    private Registered registerOwned(long owner, String pluginId, Definition definition) {
        Objects.requireNonNull(definition);
        if (!SceneOwnership.isOwnedBy(pluginId, definition.id())) {
            throw new IllegalArgumentException("Presentation ID must belong to plugin " + pluginId);
        }
        synchronized (lock) {
            requireOpen();
            if (registrations.containsKey(definition.id())) throw new IllegalArgumentException("Duplicate presentation ID: " + definition.id());
            long bytes = definition.sound() instanceof SoundAsset.PreparedOgg ogg ? ogg.encodedBytes() : 0;
            if (registrations.size() >= MAX_REGISTRATIONS || bytes > MAX_ASSET_BYTES - assetBytes) {
                throw new IllegalStateException("Presentation registration or encoded asset budget reached");
            }
            var registered = new Registered(owner, pluginId, definition, bytes);
            registrations.put(definition.id(), registered);
            assetBytes += bytes;
            return registered;
        }
    }

    boolean needsState() { synchronized (lock) { return !instances.isEmpty() || !observers.isEmpty(); } }
    boolean hasHostSoundObservers() {
        synchronized (lock) { return !closed && capabilities.pipelineActive() && capabilities.sound().hostObservation() && !observers.isEmpty(); }
    }

    /** Called by the host listener after it has excluded managed sounds, without running plugin code. */
    void captureHostSound(HostSound sound) {
        synchronized (lock) {
            if (!hasHostSoundObservers()) return;
            if (soundGap || soundQueue.size() == MAX_SOUND_QUEUE) {
                soundDropped += soundQueue.size() + 1L;
                soundQueue.clear();
                soundGap = true;
            } else soundQueue.addLast(new PendingSound(worldGeneration, Objects.requireNonNull(sound)));
        }
    }

    /** No callback runs on a worker/audio thread. Client state and event delivery precede this call. */
    void safePoint(ClientStateSnapshot snapshot, ClientStateSnapshot.LogicalTime now,
                   long observedWorld, ResourceState resources, RenderPipeline pipeline) {
        // Plugin and host code may wait for a worker that queries this service. Never hold the
        // ledger lock across those calls; it protects only our own immutable values and maps.
        Set<GpuId> types = visualTypes(pipeline);
        HostSoundBackend currentBackend;
        synchronized (lock) { if (closed) return; currentBackend = backend; }
        var soundCapabilities = soundCapabilities(currentBackend);
        List<Observed> listeners;
        long observeGeneration;
        var cancelled = new ArrayList<HostSoundBackend.Voice>();
        SoundBatch batch = null;
        synchronized (lock) {
            if (closed) return;
            time = now;
            worldGeneration = observedWorld;
            capabilities = new Capabilities(pipeline != null, types, soundCapabilities);
            resourceGeneration = resources.generation();
            Reason invalid = pipeline == null ? Reason.PIPELINE_STOPPED
                    : snapshot == null ? Reason.STATE_UNAVAILABLE
                    : !snapshot.world().loaded() ? Reason.NO_WORLD
                    : snapshot.world().generation() != observedWorld ? Reason.WORLD_CHANGED
                    : !resources.ready() ? Reason.RESOURCE_CHANGED : Reason.NONE;
            for (var instance : List.copyOf(instances.values())) {
                if (invalid != Reason.NONE) terminateLocked(instance, State.CANCELLED, invalid, cancelled);
                else if (instance.request.world().generation() != observedWorld) terminateLocked(instance, State.CANCELLED, Reason.WORLD_CHANGED, cancelled);
                else if (instance.resource >= 0 && instance.resource != resourceGeneration) terminateLocked(instance, State.CANCELLED, Reason.RESOURCE_CHANGED, cancelled);
            }
            listeners = List.copyOf(observers);
            observeGeneration = ++observationGeneration;
            if (invalid != Reason.NONE || soundQueue.stream().anyMatch(sound -> sound.world != observedWorld)) {
                soundDropped += soundQueue.size(); soundQueue.clear();
                soundGap = !listeners.isEmpty() && (soundDropped > 0 || invalid == Reason.WORLD_CHANGED);
            }
            if (snapshot != null && !listeners.isEmpty() && (!soundQueue.isEmpty() || soundGap)) {
                batch = new SoundBatch(snapshot.world(), now, soundQueue.stream().map(PendingSound::sound).toList(), soundGap, soundDropped);
                soundQueue.clear(); soundGap = false; soundDropped = 0;
            }
        }
        cancelVoices(cancelled);
        try { currentBackend.observe(observeGeneration, pipeline != null && soundCapabilities.hostObservation() && !listeners.isEmpty(), this::captureHostSound); }
        catch (RuntimeException | LinkageError e) { failure.accept(e); }
        if (batch != null) for (var listener : listeners) {
            if (!listener.isActive()) continue;
            try { listener.listener.accept(batch); }
            catch (RuntimeException | LinkageError e) { failure.accept(e); }
        }
        var steps = new ArrayList<Step>();
        cancelled.clear();
        synchronized (lock) {
            if (closed) return;
            // A single bounded pass, including starts requested by this boundary's listeners.
            for (var instance : List.copyOf(instances.values())) {
                if (snapshot == null || pipeline == null || !capabilities.pipelineActive() || !snapshot.world().loaded()
                        || snapshot.world().generation() != observedWorld || !resources.ready()) {
                    terminateLocked(instance, State.CANCELLED, snapshot == null ? Reason.STATE_UNAVAILABLE : Reason.PIPELINE_STOPPED, cancelled);
                    continue;
                }
                if (instance.request.world().generation() != observedWorld) {
                    terminateLocked(instance, State.CANCELLED, Reason.WORLD_CHANGED, cancelled); continue;
                }
                if (instance.resource >= 0 && instance.resource != resourceGeneration) {
                    terminateLocked(instance, State.CANCELLED, Reason.RESOURCE_CHANGED, cancelled); continue;
                }
                var step = advanceLocked(instance, snapshot, cancelled);
                if (step != null) steps.add(step);
            }
        }
        cancelVoices(cancelled);
        for (var step : steps) execute(step);
    }

    private Step advanceLocked(Instance instance, ClientStateSnapshot snapshot, List<HostSoundBackend.Voice> cancelled) {
        long now = time.elapsedNanos();
        if (instance.manualPause && instance.pauseStart < 0) instance.pauseStart = now;
        if (!instance.manualPause && instance.pauseStart >= 0) {
            instance.pausedNanos = add(instance.pausedNanos, Math.max(0, now - instance.pauseStart));
            instance.pauseStart = -1;
        }
        long effectiveNow = instance.pauseStart >= 0 ? instance.pauseStart : now;
        long target = add(instance.request.targetNanos(), instance.pausedNanos);
        long elapsed = Math.max(0, effectiveNow - target);
        instance.view = new View(instance.source, snapshot.world(), time, instance.request.targetNanos(),
                elapsed, instance.request.durationNanos(), resourceGeneration, instance.parameters);
        if (elapsed >= instance.request.durationNanos()) { terminateLocked(instance, State.COMPLETED, Reason.EXPIRED, cancelled); return null; }
        boolean paused = instance.manualPause || time.paused();
        if (instance.state == State.QUEUED && (effectiveNow < target || paused)) return null;
        boolean start = instance.state == State.QUEUED;
        if (start) {
            instance.resource = resourceGeneration;
            instance.state = State.RUNNING;
            starts++;
            if (instance.registered.definition.sound() != null && !capabilities.sound().supports(instance.registered.definition.sound())) {
                instance.sound = SoundStatus.UNSUPPORTED; start = false;
            }
        }
        instance.state = paused ? State.PAUSED : State.RUNNING;
        if (instance.registered.definition.visual() != null) {
            instance.visual = instance.visualBroken ? VisualStatus.FAILED
                    : capabilities.visualTypes().contains(instance.registered.id()) ? VisualStatus.AVAILABLE : VisualStatus.UNSUPPORTED;
        }
        return new Step(instance, instance.parameters, paused, start, instance.registered.definition, instance.voice, backend);
    }

    private record Step(Instance instance, Parameters parameters, boolean paused, boolean start,
                        Definition definition, HostSoundBackend.Voice voice, HostSoundBackend backend) { }

    private void execute(Step step) {
        Instance instance = step.instance;
        synchronized (lock) { if (instance.terminal()) return; }
        HostSoundBackend.Voice voice = step.voice;
        if (step.start && step.definition.sound() != null) {
            try {
                voice = step.backend.start(instance.source, step.definition.sound(), step.definition.category(), step.definition.looping(), step.parameters);
                SoundStatus result = voice == null ? SoundStatus.UNKNOWN : voice.startResult();
                boolean accepted;
                synchronized (lock) {
                    accepted = !closed && !instance.terminal() && instances.get(instance.source.instanceId()) == instance;
                    if (accepted) {
                        instance.sound = result;
                        if (result != SoundStatus.HOST_NOT_STARTED && result != SoundStatus.FAILED) instance.voice = voice;
                    }
                }
                if (!accepted || result == SoundStatus.HOST_NOT_STARTED || result == SoundStatus.FAILED) {
                    cancelVoice(voice); return;
                }
            } catch (RuntimeException | LinkageError e) {
                cancelVoice(voice);
                synchronized (lock) { if (!instance.terminal()) instance.sound = SoundStatus.FAILED; }
                failure.accept(e); return;
            }
        }
        if (voice == null) return;
        try {
            var status = voice.status();
            voice.update(step.parameters); voice.setPaused(step.paused);
            boolean active = voice.isActive();
            boolean retire = status == SoundStatus.FAILED || (!active && status != SoundStatus.UNKNOWN);
            boolean detached = false;
            synchronized (lock) {
                if (!instance.terminal() && instance.voice == voice) {
                    instance.sound = retire && status != SoundStatus.FAILED ? SoundStatus.HOST_INACTIVE : status;
                    if (retire) { instance.voice = null; detached = true; }
                }
            }
            if (detached) cancelVoice(voice);
        } catch (RuntimeException | LinkageError e) {
            boolean detached = false;
            synchronized (lock) {
                if (instance.voice == voice) { instance.voice = null; instance.sound = SoundStatus.FAILED; detached = true; }
            }
            if (detached) cancelVoice(voice);
            failure.accept(e);
        }
    }

    private Set<GpuId> visualTypes(RenderPipeline pipeline) {
        if (pipeline == null) return Set.of();
        try { return pipeline.requiresDynamicGeometry() ? Set.copyOf(pipeline.presentationVisualTypes()) : Set.of(); }
        catch (RuntimeException | LinkageError e) { failure.accept(e); return Set.of(); }
    }

    private HostSoundBackend.Capabilities soundCapabilities(HostSoundBackend currentBackend) {
        try { return Objects.requireNonNull(currentBackend.capabilities()); }
        catch (RuntimeException | LinkageError e) { failure.accept(e); return HostSoundBackend.Capabilities.NONE; }
    }

    /** Invoked through the driver's existing contribution merge, before scene admission/plan selection. */
    List<DynamicSceneMesh> contribute(SceneSnapshot scene, RenderPipeline pipeline) {
        synchronized (lock) {
            if (closed || pipeline == null || !scene.worldLoaded() || instances.isEmpty()) return List.of();
        }
        Set<GpuId> types = visualTypes(pipeline);
        List<Instance> admitted;
        synchronized (lock) {
            if (closed || pipeline == null || !scene.worldLoaded()) return List.of();
            admitted = instances.values().stream().filter(instance -> instance.state != State.QUEUED
                    && !instance.visualBroken && types.contains(instance.registered.id()) && instance.registered.definition.visual() != null).toList();
        }
        var merged = new ArrayList<DynamicSceneMesh>();
        for (var instance : admitted) {
            View view;
            Visual visual;
            synchronized (lock) { if (instance.terminal()) continue; view = instance.view; visual = instance.registered.definition.visual(); visualCalls++; }
            try {
                var returned = Objects.requireNonNull(visual.contribute(view, scene), "Presentation visual returned null");
                var geometry = new ArrayList<DynamicSceneMesh>();
                // A plugin-supplied list can itself run code. Validate and copy it outside the ledger lock.
                for (var mesh : returned) {
                    if (geometry.size() == MAX_MESHES_PER_INSTANCE) throw new IllegalStateException("Presentation mesh limit reached");
                    geometry.add(Objects.requireNonNull(mesh, "Presentation mesh"));
                }
                synchronized (lock) {
                    if (instance.terminal()) continue;
                    merged.addAll(geometry);
                    instance.visual = geometry.isEmpty() ? VisualStatus.AVAILABLE : VisualStatus.CONTRIBUTED;
                }
            } catch (RuntimeException | LinkageError e) {
                synchronized (lock) { if (!instance.terminal()) { instance.visual = VisualStatus.FAILED; instance.visualBroken = true; } }
                failure.accept(e);
            }
        }
        return merged;
    }

    void stopPipeline() {
        var cancelled = new ArrayList<HostSoundBackend.Voice>();
        HostSoundBackend currentBackend;
        long observeGeneration;
        synchronized (lock) {
            capabilities = new Capabilities(false, Set.of(), capabilities.sound());
            for (var instance : List.copyOf(instances.values())) terminateLocked(instance, State.CANCELLED, Reason.PIPELINE_STOPPED, cancelled);
            soundQueue.clear(); soundGap = false; soundDropped = 0;
            currentBackend = backend;
            observeGeneration = ++observationGeneration;
        }
        cancelVoices(cancelled);
        try { currentBackend.observe(observeGeneration, false, this::captureHostSound); } catch (RuntimeException | LinkageError e) { failure.accept(e); }
    }

    void pipelineActivated(RenderPipeline pipeline) {
        var types = visualTypes(pipeline);
        HostSoundBackend currentBackend;
        synchronized (lock) { currentBackend = backend; }
        var sound = soundCapabilities(currentBackend);
        synchronized (lock) {
            if (!closed) capabilities = new Capabilities(true, types, sound);
        }
    }

    void releaseOwner(long owner) {
        var cancelled = new ArrayList<HostSoundBackend.Voice>();
        synchronized (lock) {
            for (var instance : List.copyOf(instances.values())) if (instance.source.ownerInstance() == owner) terminateLocked(instance, State.CANCELLED, Reason.OWNER_RELEASED, cancelled);
            for (var registration : List.copyOf(registrations.values())) if (registration.owner == owner) unregisterLocked(registration, cancelled);
            observers.removeIf(observer -> { if (observer.owner == owner) { observer.active = false; return true; } return false; });
            if (observers.isEmpty()) { soundQueue.clear(); soundGap = false; soundDropped = 0; }
        }
        cancelVoices(cancelled);
        disableObservationIfUnused();
    }

    @Override public void close() {
        var cancelled = new ArrayList<HostSoundBackend.Voice>();
        HostSoundBackend currentBackend;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            for (var instance : List.copyOf(instances.values())) terminateLocked(instance, State.CANCELLED, Reason.SERVICE_CLOSED, cancelled);
            for (var registration : List.copyOf(registrations.values())) unregisterLocked(registration, cancelled);
            observers.forEach(observer -> observer.active = false); observers.clear(); soundQueue.clear();
            currentBackend = backend;
            capabilities = Capabilities.UNAVAILABLE;
        }
        cancelVoices(cancelled);
        try { currentBackend.close(); } catch (RuntimeException | LinkageError e) { failure.accept(e); }
    }

    Metrics metrics() {
        synchronized (lock) { return new Metrics(registrations.size(), instances.size(), observers.size(), peakHandles, rejected, starts, visualCalls, assetBytes, soundQueue.size()); }
    }
    record Metrics(int registrations, int handles, int observers, long peakHandles, long rejected, long starts, long visualCalls, long assetBytes, int soundQueued) { }
    private record PendingSound(long world, HostSound sound) { }
    private void requireOpen() { if (closed) throw new IllegalStateException("Presentation service is closed"); }
    private static long add(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }

    private void terminateLocked(Instance instance, State state, Reason reason, List<HostSoundBackend.Voice> cancelled) {
        if (instance.terminal()) return;
        instance.state = state; instance.reason = reason;
        instances.remove(instance.source.instanceId());
        if (instance.voice != null) {
            cancelled.add(instance.voice); instance.voice = null; instance.sound = SoundStatus.STOP_REQUESTED;
        }
    }
    private void cancelVoice(HostSoundBackend.Voice voice) {
        if (voice != null) try { voice.cancel(); } catch (RuntimeException | LinkageError e) { failure.accept(e); }
    }
    private void cancelVoices(List<HostSoundBackend.Voice> voices) { voices.forEach(this::cancelVoice); }

    private void unregisterLocked(Registered registered, List<HostSoundBackend.Voice> cancelled) {
        if (registered.released) return;
        registered.released = true; registrations.remove(registered.id(), registered); assetBytes -= registered.bytes;
        for (var instance : List.copyOf(instances.values())) if (instance.registered == registered) {
            terminateLocked(instance, State.CANCELLED, Reason.REGISTRATION_CLOSED, cancelled);
        }
        registered.definition = null;
    }

    private void disableObservationIfUnused() {
        HostSoundBackend currentBackend;
        long observeGeneration;
        synchronized (lock) {
            if (!observers.isEmpty()) return;
            soundQueue.clear(); soundGap = false; soundDropped = 0; currentBackend = backend; observeGeneration = ++observationGeneration;
        }
        try { currentBackend.observe(observeGeneration, false, this::captureHostSound); } catch (RuntimeException | LinkageError e) { failure.accept(e); }
    }

    private final class Registered implements Registration {
        final long owner, bytes;
        final String pluginId;
        Definition definition;
        final GpuId id;
        boolean released;
        Registered(long owner, String pluginId, Definition definition, long bytes) {
            this.owner = owner; this.pluginId = pluginId; this.definition = definition; this.bytes = bytes;
            this.id = definition.id();
        }
        public GpuId id() { return id; }
        public boolean isClosed() { synchronized (lock) { return released || closed; } }
        public Handle start(Request request) {
            Objects.requireNonNull(request); validateParameters(request.parameters());
            synchronized (lock) {
                var instance = new Instance(this, request, ++nextId);
                if (closed || released) { instance.state = State.REJECTED; instance.reason = closed ? Reason.SERVICE_CLOSED : Reason.REGISTRATION_CLOSED; }
                else if (instances.size() >= MAX_HANDLES || instances.values().stream().filter(value -> value.source.ownerInstance() == owner).count() >= MAX_HANDLES_PER_OWNER) {
                    instance.state = State.REJECTED; instance.reason = Reason.BACKPRESSURE; rejected++;
                } else if (!request.world().loaded()) { instance.state = State.REJECTED; instance.reason = Reason.NO_WORLD; }
                else {
                    instances.put(instance.source.instanceId(), instance);
                    peakHandles = Math.max(peakHandles, instances.size());
                }
                return instance;
            }
        }
        public void close() {
            var cancelled = new ArrayList<HostSoundBackend.Voice>();
            synchronized (lock) {
                unregisterLocked(this, cancelled);
            }
            cancelVoices(cancelled);
        }
    }

    private final class Instance implements Handle {
        final Registered registered;
        final Request request;
        final Source source;
        State state = State.QUEUED;
        Reason reason = Reason.NONE;
        VisualStatus visual;
        SoundStatus sound;
        Parameters parameters;
        View view;
        HostSoundBackend.Voice voice;
        long resource, pausedNanos, pauseStart = -1;
        boolean manualPause, visualBroken;
        Instance(Registered registered, Request request, long id) {
            this.registered = registered; this.request = request; this.parameters = request.parameters();
            this.source = new Source(registered.pluginId, registered.owner, id, registered.id());
            this.resource = resourceGeneration;
            visual = registered.definition == null || registered.definition.visual() == null ? VisualStatus.NONE : VisualStatus.PENDING;
            sound = registered.definition == null || registered.definition.sound() == null ? SoundStatus.NONE : SoundStatus.PENDING;
            view = new View(source, request.world(), time, request.targetNanos(), 0, request.durationNanos(), resource, parameters);
        }
        boolean terminal() { return state == State.COMPLETED || state == State.CANCELLED || state == State.REJECTED; }
        public Snapshot snapshot() { synchronized (lock) { return new Snapshot(state, reason, visual, sound, view); } }
        public boolean update(Parameters parameters) {
            Objects.requireNonNull(parameters); validateParameters(parameters);
            synchronized (lock) { if (terminal()) return false; this.parameters = parameters; visualBroken = false; return true; }
        }
        public boolean setPaused(boolean paused) { synchronized (lock) { if (terminal()) return false; manualPause = paused; return true; } }
        public void close() {
            var cancelled = new ArrayList<HostSoundBackend.Voice>();
            synchronized (lock) { terminateLocked(this, State.CANCELLED, Reason.REQUESTED, cancelled); }
            cancelVoices(cancelled);
        }
    }

    private final class Observed implements Subscription {
        final long owner;
        final Consumer<SoundBatch> listener;
        boolean active = true;
        Observed(long owner, Consumer<SoundBatch> listener) { this.owner = owner; this.listener = listener; }
        public boolean isActive() { synchronized (lock) { return active && !closed; } }
        public void close() {
            synchronized (lock) { active = false; observers.remove(this); }
            disableObservationIfUnused();
        }
    }

    private static void validateParameters(Parameters parameters) {
        int[] budget = {1024, 65536};
        validateValue(parameters.custom(), budget, 0);
    }
    private static void validateValue(EventValue value, int[] budget, int depth) {
        if (--budget[0] < 0 || depth > 16) throw new IllegalArgumentException("Presentation parameter tree exceeds limits");
        if (value instanceof EventValue.Text text) budget[1] -= text.value().length() * 2;
        else if (value instanceof EventValue.Array array) for (var child : array.values()) validateValue(child, budget, depth + 1);
        else if (value instanceof EventValue.ObjectValue object) for (var entry : object.values().entrySet()) {
            budget[1] -= entry.getKey().length() * 2; validateValue(entry.getValue(), budget, depth + 1);
        }
        if (budget[1] < 0) throw new IllegalArgumentException("Presentation text parameters exceed 64 KiB");
    }
}
