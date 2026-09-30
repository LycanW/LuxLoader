package dev.luxloader.mc.hooks;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import com.mojang.blaze3d.audio.SoundBuffer;
import dev.luxloader.api.GpuId;
import dev.luxloader.api.presentation.PresentationService;
import dev.luxloader.api.presentation.SoundAsset;
import dev.luxloader.api.resource.ResourceKey;
import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual MC transforms and channel executor code; only native channel operations are recorded. */
class SoundBridgeOfflineTest {
    @Test void productionSoundAndClientHooksApplyToActualMinecraftBytecode() throws Exception {
        var engine = node(OfflineMixinHarness.transform("net.minecraft.client.sounds.SoundEngine", Path.of("build/t5-mixin")));
        assertEquals(1, calls(engine, "dev/luxloader/mc/hooks/ManagedSoundInstance", "bindChannel"));
        assertEquals(1, calls(engine, "dev/luxloader/mc/hooks/ManagedSoundInstance", "consumeDecoded"));
        assertEquals(1, calls(engine, "dev/luxloader/mc/hooks/ManagedSoundInstance", "pluginStream"));
        assertEquals(1, calls(engine, "dev/luxloader/mc/hooks/ManagedSoundInstance", "noteBridgeInstalled"));
        var handle = node(OfflineMixinHarness.transform("net.minecraft.client.sounds.ChannelAccess$ChannelHandle", Path.of("build/t5-mixin")));
        assertTrue(handle.interfaces.contains("dev/luxloader/mc/hooks/ManagedSoundChannel"));
        assertTrue(handle.methods.stream().anyMatch(method -> method.name.equals("luxloader$consumeManaged")));
        var access = node(OfflineMixinHarness.transform("net.minecraft.client.sounds.ChannelAccess", Path.of("build/t5-mixin")));
        assertTrue(access.methods.stream().anyMatch(method -> method.name.equals("luxloader$soundExecutor")));
        var client = node(OfflineMixinHarness.transform("net.minecraft.client.Minecraft", Path.of("build/t5-mixin")));
        assertEquals(1, calls(client, "dev/luxloader/mc/hooks/RenderHooks", "onClientTick"));
        assertEquals(1, calls(client, "dev/luxloader/mc/hooks/RenderHooks", "onClientSafePoint"));
        var play = engine.methods.stream().filter(method -> method.name.equals("play")).findFirst().orElseThrow();
        assertEquals(2, java.util.stream.StreamSupport.stream(play.instructions.spliterator(), false)
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.name.contains("guardOwnedDecoded")).count(),
                "Both the cached buffer and streaming continuations must use the guard");
    }

    @Test void cancelledBeforeDecodeAndAfterExecutorEnqueueNeverStartsAndClosesStreamsOnce() throws Exception {
        for (boolean afterEnqueue : new boolean[]{false, true}) {
            var fixture = new Fixture(); var sound = fixture.sound(1); var stream = new Stream();
            var future = new CompletableFuture<Object>(); fixture.consume(sound, future);
            if (afterEnqueue) { future.complete(stream); assertEquals(1, fixture.queue.size()); fixture.cancel(sound); }
            else { fixture.cancel(sound); future.complete(stream); }
            fixture.drain(); assertEquals(0, fixture.count("plays")); assertEquals(0, fixture.count("attaches")); assertEquals(1, stream.closes);
        }
    }

    @Test void retiredHostHandleDisposesTheUnconsumedStreamAndOldCancellationCannotStopANewInstance() throws Exception {
        var fixture = new Fixture(); var retired = fixture.sound(1); var stream = new Stream();
        var future = new CompletableFuture<Object>(); fixture.consume(retired, future); future.complete(stream);
        var field = fixture.handle.getClass().getDeclaredField("channel"); field.setAccessible(true); field.set(fixture.handle, null);
        fixture.drain(); assertEquals(1, stream.closes); assertEquals(0, fixture.count("plays"));
        var fresh = new Fixture(); var newSound = fresh.sound(2); fixture.cancel(retired);
        var newStream = new Stream(); fresh.consume(newSound, CompletableFuture.completedFuture(newStream)); fresh.drain();
        assertEquals(1, fresh.count("plays")); assertEquals(1, fresh.count("attaches")); assertEquals(0, newStream.closes);
    }

    @Test void cachedStaticBufferIsBorrowedAndNeverDiscardedByCancelledManagedPlayback() throws Exception {
        var fixture = new Fixture(); var sound = fixture.sound(1);
        var bytes = ByteBuffer.allocate(8); var buffer = new SoundBuffer(bytes, new AudioFormat(44100, 16, 1, true, false));
        fixture.consume(sound, CompletableFuture.completedFuture(buffer)); fixture.cancel(sound); fixture.drain();
        assertEquals(0, fixture.count("plays")); assertEquals(0, fixture.count("attaches"));
        var data = SoundBuffer.class.getDeclaredField("data"); data.setAccessible(true);
        assertSame(bytes, data.get(buffer), "The host's shared cache buffer remains owned by the host");
    }

    @Test void pauseWhileDecodeIsPendingDefersNativePlayAndCancellationRemainsEffective() throws Exception {
        var fixture = new Fixture(); var sound = fixture.sound(1); fixture.pause(sound, true); fixture.drain();
        var stream = new Stream(); fixture.consume(sound, CompletableFuture.completedFuture(stream)); fixture.drain();
        assertEquals(1, fixture.count("attaches")); assertEquals(0, fixture.count("plays"));
        fixture.pause(sound, false); fixture.drain(); assertEquals(1, fixture.count("plays"));
        var cancelled = fixture.sound(2); fixture.pause(cancelled, true); fixture.drain();
        fixture.consume(cancelled, CompletableFuture.completedFuture(new Stream())); fixture.cancel(cancelled); fixture.drain();
        fixture.pause(cancelled, false); fixture.drain(); assertEquals(1, fixture.count("plays"));
    }

    @Test void preparedPluginBytesUseTheProductionVorbisDecoderForSingleAndLoopingStreams() throws Exception {
        String sample = System.getProperty("luxloader.test.oggSample");
        org.junit.jupiter.api.Assumptions.assumeTrue(sample != null, "An explicit external Ogg sample is required; no game asset is packaged");
        byte[] bytes = Files.readAllBytes(Path.of(sample));
        var asset = new SoundAsset.PreparedOgg(new ResourceKey("test", "plugin-owned-tone"), 7, ByteBuffer.wrap(bytes));
        Arrays.fill(bytes, (byte)0); // Production must consume the copied plugin-owned bytes.
        var fixture = new Fixture();
        long singlePcmBytes = 0;
        for (boolean looping : new boolean[]{false, true}) {
            var sound = fixture.sound(looping ? 11 : 10, asset, looping);
            var future = (CompletableFuture<?>)fixture.soundClass.getMethod("pluginStream", boolean.class).invoke(sound, looping);
            try (var stream = (AudioStream)future.get(5, TimeUnit.SECONDS)) {
                assertTrue(stream.getFormat().getSampleRate() > 0); assertTrue(stream.getFormat().getChannels() > 0);
                var pcm = stream.read(4096); assertTrue(pcm.remaining() > 0);
                long total = pcm.remaining();
                if (looping) {
                    // The host may return short reads; it resets only after a read reaches EOF.
                    for (int i = 0; i < 12; i++) { var next = stream.read(256_000); assertTrue(next.hasRemaining()); total += next.remaining(); }
                    assertTrue(total > singlePcmBytes * 2, "The actual host looping decoder must reopen the owned input");
                } else {
                    for (int i = 0; i < 256; i++) { var next = stream.read(8192); if (!next.hasRemaining()) break; total += next.remaining(); }
                    singlePcmBytes = total;
                }
                System.out.println("prepared-ogg looping=" + looping + " encoded=" + asset.encodedBytes()
                        + " format=" + stream.getFormat() + " firstPcmBytes=" + pcm.remaining() + " totalPcmBytes=" + total);
            }
        }
    }

    @Test void badPreparedOggFailsTheProductionDecoderWithoutAHostStartClaim() throws Exception {
        var fixture = new Fixture();
        var asset = new SoundAsset.PreparedOgg(new ResourceKey("test", "invalid-tone"), 8,
                ByteBuffer.wrap(new byte[]{'O', 'g', 'g', 'S', 0, 0, 0, 0}));
        var sound = fixture.sound(12, asset, false);
        var decoded = (CompletableFuture<?>)fixture.soundClass.getMethod("pluginStream", boolean.class).invoke(sound, false);
        var consumed = (CompletableFuture<?>)fixture.soundClass.getMethod("consumeDecoded", CompletableFuture.class, fixture.handle.getClass())
                .invoke(sound, decoded, fixture.handle);
        assertThrows(java.util.concurrent.ExecutionException.class, () -> consumed.get(5, TimeUnit.SECONDS));
        var failed = fixture.soundClass.getDeclaredMethod("decodeFailed"); failed.setAccessible(true);
        assertEquals(true, failed.invoke(sound)); assertEquals(0, fixture.count("plays")); assertEquals(0, fixture.count("attaches"));
    }

    @Test void rawHostOggUsesActualSoundResolutionAndUpdatesReachTheTickableValuesAndChannelQueue() throws Exception {
        var fixture = new Fixture(); var key = new ResourceKey("test", "sounds/custom/tone.ogg");
        var sound = fixture.sound(13, new SoundAsset.HostOgg(key, true), false);
        fixture.soundClass.getMethod("getOrResolve", net.minecraft.client.sounds.SoundManager.class).invoke(sound, new Object[]{null});
        var host = (net.minecraft.client.resources.sounds.SoundInstance)sound;
        assertEquals("test:sounds/custom/tone.ogg", host.getSound().getPath().toString());
        var parameters = new PresentationService.Parameters(7, 8, 9, 2, 0.3f, 1.5f, true,
                dev.luxloader.api.event.EventValue.ObjectValue.empty());
        var update = fixture.soundClass.getDeclaredMethod("update", PresentationService.Parameters.class); update.setAccessible(true);
        update.invoke(sound, parameters); fixture.drain();
        assertEquals(7, host.getX()); assertEquals(8, host.getY()); assertEquals(9, host.getZ());
        assertEquals(0.3f, host.getVolume()); assertEquals(1.5f, host.getPitch()); assertTrue(host.isRelative());
        assertEquals(1, fixture.count("relatives")); assertEquals(0, fixture.count("plays"));
    }

    private static ClassNode node(byte[] bytes) { var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static long calls(ClassNode node, String owner, String name) {
        long result = 0; for (var method : node.methods) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) result++;
        return result;
    }
    static final class Stream implements AudioStream {
        int closes;
        public AudioFormat getFormat() { return new AudioFormat(44100, 16, 1, true, false); }
        public ByteBuffer read(int bytes) { return ByteBuffer.allocate(0); }
        public void close() { closes++; }
    }

    static final class Fixture {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final ClassLoader loader;
        final Object channel, handle;
        final Class<?> soundClass;
        final List<CompletableFuture<?>> consumers = new ArrayList<>();
        Fixture() throws Exception {
            var definitions = new HashMap<String, byte[]>();
            for (String name : List.of("net.minecraft.client.sounds.ChannelAccess", "net.minecraft.client.sounds.ChannelAccess$ChannelHandle"))
                definitions.put(name, OfflineMixinHarness.transform(name, Path.of("build/t5-mixin")));
            String soundName = "dev.luxloader.mc.hooks.ManagedSoundInstance";
            try (var input = getClass().getClassLoader().getResourceAsStream(soundName.replace('.', '/') + ".class")) { definitions.put(soundName, input.readAllBytes()); }
            // Include the anonymous decoding/lease classes in the same runtime loader as their owner.
            for (int i = 1; i <= 2; i++) {
                String name = soundName + "$" + i;
                try (var input = getClass().getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) { if (input != null) definitions.put(name, input.readAllBytes()); }
            }
            String recording = "dev.luxloader.mc.hooks.RecordedNativeChannel";
            definitions.put(recording, recordingChannel(recording));
            loader = new ClassLoader(getClass().getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        var loaded = findLoadedClass(name);
                        if (loaded == null && definitions.containsKey(name)) {
                            var bytes = definitions.get(name); loaded = defineClass(name, bytes, 0, bytes.length);
                        }
                        if (loaded == null) loaded = super.loadClass(name, false);
                        if (resolve) resolveClass(loaded); return loaded;
                    }
                }
            };
            var unsafeClass = Class.forName("sun.misc.Unsafe"); var field = unsafeClass.getDeclaredField("theUnsafe"); field.setAccessible(true);
            channel = unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), loader.loadClass(recording));
            var accessClass = loader.loadClass("net.minecraft.client.sounds.ChannelAccess");
            var access = accessClass.getConstructor(Library.class, Executor.class).newInstance(null, (Executor)queue::add);
            var handleClass = loader.loadClass("net.minecraft.client.sounds.ChannelAccess$ChannelHandle");
            handle = handleClass.getConstructor(accessClass, Channel.class).newInstance(access, channel);
            soundClass = loader.loadClass(soundName);
        }
        Object sound(long id) throws Exception {
            return sound(id, new SoundAsset.HostEvent(new ResourceKey("test", "tone")), false);
        }
        Object sound(long id, SoundAsset asset, boolean looping) throws Exception {
            var constructor = soundClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            var sound = constructor.newInstance(new PresentationService.Source("test:owner", id, id, new GpuId("test", "owner/tone")),
                    asset, PresentationService.Category.PLAYERS, looping, PresentationService.Parameters.at(0, 0, 0));
            soundClass.getMethod("bindChannel", handle.getClass()).invoke(sound, handle); return sound;
        }
        void consume(Object sound, CompletableFuture<?> future) throws Exception {
            consumers.add((CompletableFuture<?>)soundClass.getMethod("consumeDecoded", CompletableFuture.class, handle.getClass()).invoke(sound, future, handle));
        }
        void cancel(Object sound) throws Exception { var method = soundClass.getDeclaredMethod("cancel"); method.setAccessible(true); method.invoke(sound); }
        void pause(Object sound, boolean paused) throws Exception { var method = soundClass.getDeclaredMethod("setPaused", boolean.class); method.setAccessible(true); method.invoke(sound, paused); }
        void drain() {
            while (!queue.isEmpty()) queue.removeFirst().run();
            for (var consumer : consumers) if (consumer.isDone()) consumer.join();
        }
        int count(String name) throws Exception { return channel.getClass().getField(name).getInt(channel); }
    }

    private static byte[] recordingChannel(String name) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); var internal = name.replace('.', '/');
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "com/mojang/blaze3d/audio/Channel", null);
        for (String field : List.of("plays", "attaches", "pauses", "stops", "relatives")) writer.visitField(Opcodes.ACC_PUBLIC, field, "I", null, null).visitEnd();
        Map<String, String> methods = Map.of("play", "()V", "unpause", "()V", "pause", "()V", "stop", "()V",
                "setRelative", "(Z)V", "attachBufferStream", "(Lnet/minecraft/client/sounds/AudioStream;)V", "attachStaticBuffer", "(Lcom/mojang/blaze3d/audio/SoundBuffer;)V");
        methods.forEach((method, descriptor) -> {
            String field = method.startsWith("attach") ? "attaches" : method.equals("pause") ? "pauses" : method.equals("stop") ? "stops" : method.equals("setRelative") ? "relatives" : "plays";
            var body = writer.visitMethod(Opcodes.ACC_PUBLIC, method, descriptor, null, null); body.visitCode();
            body.visitVarInsn(Opcodes.ALOAD, 0); body.visitInsn(Opcodes.DUP); body.visitFieldInsn(Opcodes.GETFIELD, internal, field, "I");
            body.visitInsn(Opcodes.ICONST_1); body.visitInsn(Opcodes.IADD); body.visitFieldInsn(Opcodes.PUTFIELD, internal, field, "I");
            body.visitInsn(Opcodes.RETURN); body.visitMaxs(0, 0); body.visitEnd();
        });
        writer.visitEnd(); return writer.toByteArray();
    }
}
