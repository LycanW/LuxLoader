package dev.luxloader.mc.hooks;

import dev.luxloader.api.host.HostSoundBackend;
import dev.luxloader.api.presentation.PresentationService;
import dev.luxloader.api.presentation.SoundAsset;
import dev.luxloader.api.resource.ResourceKey;
import dev.luxloader.mc.ManagedSoundObservation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Minecraft 26.3 sound bridge shared by both loaders, independent of the graphics backend. */
public final class MinecraftSoundBackend implements HostSoundBackend {
    private static final Capabilities SUPPORTED = new Capabilities(true, true, true, true, true, true, true);
    private final Minecraft minecraft;
    private final SoundManager manager;
    private final Set<ManagedSoundInstance> owned = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final ManagedSoundObservation observation = new ManagedSoundObservation();
    private final SoundEventListener listener;

    public MinecraftSoundBackend(Minecraft minecraft) {
        this.minecraft = minecraft;
        this.manager = minecraft.getSoundManager();
        listener = (instance, event, range) -> observation.capture(instance instanceof ManagedSoundInstance,
                minecraft.isSameThread(), () -> {
            // SoundEngine notifies before decoding/playback and periodically for looping sounds.
            // Filter every managed source before allocating any event, not merely this owner.
            var sound = instance.getSound();
            if (sound == null) return null;
            var asset = sound.getPath(); var id = instance.getIdentifier();
            return new PresentationService.HostSound(new ResourceKey(id.getNamespace(), id.getPath()),
                    new ResourceKey(asset.getNamespace(), asset.getPath()),
                    new PresentationService.Parameters(instance.getX(), instance.getY(), instance.getZ(), 1,
                            Math.clamp(instance.getVolume(), 0f, 1f), Math.clamp(instance.getPitch(), 0.5f, 2f),
                            instance.isRelative(), dev.luxloader.api.event.EventValue.ObjectValue.empty()));
        });
    }

    @Override public Capabilities capabilities() { return closed || !ManagedSoundInstance.bridgeInstalled() ? Capabilities.NONE : SUPPORTED; }

    @Override public Voice start(PresentationService.Source source, SoundAsset asset, PresentationService.Category category,
                                 boolean looping, PresentationService.Parameters parameters) {
        requireClientThread();
        if (closed) return null;
        var instance = new ManagedSoundInstance(source, asset, category, looping, parameters);
        owned.add(instance);
        PresentationService.SoundStatus result;
        try {
            result = switch (manager.play(instance)) {
                case STARTED -> PresentationService.SoundStatus.HOST_STARTED;
                case STARTED_SILENTLY -> PresentationService.SoundStatus.HOST_STARTED_SILENTLY;
                case NOT_STARTED -> PresentationService.SoundStatus.HOST_NOT_STARTED;
            };
            if (result != PresentationService.SoundStatus.HOST_NOT_STARTED && !instance.startHookObserved()) {
                cancel(instance);
                throw new IllegalStateException("Managed sound start guard did not run");
            }
        } catch (RuntimeException | LinkageError e) { cancel(instance); throw e; }
        var startResult = result;
        return new Voice() {
            public PresentationService.SoundStatus startResult() { return startResult; }
            public PresentationService.SoundStatus status() { return instance.decodeFailed() ? PresentationService.SoundStatus.FAILED : startResult; }
            public boolean isActive() { requireClientThread(); return !instance.isStopped() && manager.isActive(instance); }
            public void update(PresentationService.Parameters value) { requireClientThread(); instance.update(value); }
            public void setPaused(boolean value) { requireClientThread(); instance.setPaused(value); }
            public void cancel() { MinecraftSoundBackend.this.cancel(instance); }
        };
    }

    private void cancel(ManagedSoundInstance instance) {
        if (!instance.cancel()) return;
        owned.remove(instance);
        // Marking first guards decode completions even if no later client tick will run.
        onClient(() -> manager.stop(instance));
    }

    @Override public void observe(long generation, boolean enabled, Consumer<PresentationService.HostSound> sink) {
        if (observation.configure(generation, enabled, sink)) {
            onClient(() -> { manager.removeListener(listener); if (observation.enabled() && !closed) manager.addListener(listener); });
        }
    }

    private void onClient(Runnable action) { if (minecraft.isSameThread()) action.run(); else minecraft.execute(action); }
    private void requireClientThread() { if (!minecraft.isSameThread()) throw new IllegalStateException("Sound host access requires the client thread"); }
    @Override public void close() {
        if (closed) return;
        closed = true; observation.close();
        for (var instance : Set.copyOf(owned)) cancel(instance);
        onClient(() -> manager.removeListener(listener));
    }
}
