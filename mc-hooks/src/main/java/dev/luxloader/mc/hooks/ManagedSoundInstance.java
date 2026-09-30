package dev.luxloader.mc.hooks;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import dev.luxloader.api.presentation.PresentationService;
import dev.luxloader.api.presentation.SoundAsset;
import dev.luxloader.mc.ManagedSoundPlayback;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.*;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/** Host-owned sound instance. Neither this object nor its channel escapes into the public SDK. */
public final class ManagedSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    private final PresentationService.Source provenance;
    private final SoundAsset asset;
    private final ManagedSoundPlayback playback = new ManagedSoundPlayback();
    private ChannelAccess.ChannelHandle channel;
    private volatile PresentationService.Parameters parameters;
    private volatile boolean startHookObserved;
    private volatile boolean decodeFailed;
    private static volatile boolean bridgeInstalled;

    ManagedSoundInstance(PresentationService.Source provenance, SoundAsset asset,
                         PresentationService.Category category, boolean looping,
                         PresentationService.Parameters parameters) {
        super(identifier(asset), SoundSource.valueOf(category.name()), SoundInstance.createUnseededRandom());
        this.provenance = provenance; this.asset = asset; this.looping = looping;
        this.parameters = parameters;
    }

    private static Identifier identifier(SoundAsset asset) {
        var key = asset instanceof SoundAsset.HostEvent event ? event.id()
                : asset instanceof SoundAsset.HostOgg ogg ? ogg.id() : ((SoundAsset.PreparedOgg)asset).id();
        String path = key.path();
        if (asset instanceof SoundAsset.HostOgg) path = path.substring(7, path.length() - 4);
        return Identifier.fromNamespaceAndPath(key.namespace(), path);
    }

    @Override public WeighedSoundEvents getOrResolve(SoundManager manager) {
        if (asset instanceof SoundAsset.HostEvent) return super.getOrResolve(manager);
        sound = new Sound(identifier, ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE,
                asset instanceof SoundAsset.PreparedOgg || ((SoundAsset.HostOgg)asset).streaming(), false, 16);
        soundEvent = new WeighedSoundEvents(identifier, null);
        soundEvent.addSound(sound);
        return soundEvent;
    }

    @Override public void tick() { }
    @Override public boolean isStopped() { return playback.cancelled(); }
    @Override public boolean canPlaySound() { return !playback.cancelled(); }
    @Override public boolean canStartSilent() { return true; }
    @Override public double getX() { return parameters.x(); }
    @Override public double getY() { return parameters.y(); }
    @Override public double getZ() { return parameters.z(); }
    @Override public float getVolume() { return parameters.volume() * super.getVolume(); }
    @Override public float getPitch() { return parameters.pitch() * super.getPitch(); }
    @Override public boolean isRelative() { return parameters.relativeSound(); }

    public PresentationService.Source provenance() { return provenance; }
    void update(PresentationService.Parameters parameters) {
        boolean relativeChanged = this.parameters.relativeSound() != parameters.relativeSound();
        this.parameters = parameters;
        var handle = channel;
        if (relativeChanged && handle != null) handle.execute(nativeChannel -> {
            if (!playback.cancelled()) nativeChannel.setRelative(parameters.relativeSound());
        });
    }
    boolean cancel() { return playback.cancel(); }
    boolean startHookObserved() { return startHookObserved; }
    boolean decodeFailed() { return decodeFailed; }
    public static void noteBridgeInstalled() { bridgeInstalled = true; }
    public static boolean bridgeInstalled() { return bridgeInstalled; }

    /** Invoked by the play hook at the host's first channel configuration action. */
    public void bindChannel(ChannelAccess.ChannelHandle channel) {
        if (!(channel instanceof ManagedSoundChannel)) throw new IllegalStateException("Managed sound channel guard is unavailable");
        this.channel = channel; startHookObserved = true;
    }

    void setPaused(boolean paused) {
        playback.markPaused(paused);
        var handle = channel;
        if (handle != null) handle.execute(nativeChannel -> playback.applyPause(nativeChannel::pause,
                nativeChannel::play, nativeChannel::unpause));
    }

    /** Both the decode-completion and audio-executor cancellation checks are on this actual path. */
    public CompletableFuture<Void> consumeDecoded(CompletableFuture<?> decoded, ChannelAccess.ChannelHandle handle) {
        return playback.whenDecoded(decoded, value -> ((ManagedSoundChannel)handle).luxloader$consumeManaged(nativeChannel ->
                playback.consume(new ManagedSoundPlayback.DecodedLease() {
                    public void attach() {
                        if (value instanceof AudioStream stream) nativeChannel.attachBufferStream(stream);
                        else nativeChannel.attachStaticBuffer((SoundBuffer)value);
                    }
                    public void discard() { discardDecoded(value); }
                }, nativeChannel::play), () -> discardDecoded(value)), ManagedSoundInstance::discardDecoded)
                .whenComplete((ignored, error) -> { if (error != null && !playback.cancelled()) decodeFailed = true; });
    }

    public static void discardDecoded(Object value) {
        if (value instanceof AudioStream stream) {
            try { stream.close(); } catch (IOException ignored) { /* The host no longer consumes this lease. */ }
        }
    }

    /** Prepared plugin bytes use Minecraft's Vorbis decoder and existing IO pool, never a file path. */
    public CompletableFuture<AudioStream> pluginStream(boolean looping) {
        var ogg = (SoundAsset.PreparedOgg)asset;
        return CompletableFuture.supplyAsync(() -> {
            if (playback.cancelled()) throw new java.util.concurrent.CancellationException("Presentation cancelled before sound decode");
            InputStream input = new InputStream() {
                private final ByteBuffer bytes = ogg.encoded();
                @Override public int read() { return bytes.hasRemaining() ? Byte.toUnsignedInt(bytes.get()) : -1; }
                @Override public int read(byte[] target, int offset, int length) {
                    java.util.Objects.checkFromIndexSize(offset, length, target.length);
                    if (length == 0) return 0;
                    if (!bytes.hasRemaining()) return -1;
                    int count = Math.min(length, bytes.remaining()); bytes.get(target, offset, count); return count;
                }
            };
            try {
                return looping ? new LoopingAudioStream(JOrbisAudioStream::new, input) : new JOrbisAudioStream(input);
            } catch (IOException | RuntimeException e) {
                try { input.close(); } catch (IOException ignored) { }
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Util.nonCriticalIoPool());
    }

    public boolean hasPluginAsset() { return asset instanceof SoundAsset.PreparedOgg; }
}
