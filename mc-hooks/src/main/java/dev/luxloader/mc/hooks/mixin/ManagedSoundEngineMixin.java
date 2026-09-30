package dev.luxloader.mc.hooks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.luxloader.mc.hooks.ManagedSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.*;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Guards late asset attachment without changing the host's shared buffer cache or unmanaged sounds. */
@Mixin(SoundEngine.class)
abstract class ManagedSoundEngineMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void luxloader$noteSoundBridge(CallbackInfo callback) { ManagedSoundInstance.noteBridgeInstalled(); }

    @WrapOperation(method = "play", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/ChannelAccess$ChannelHandle;execute(Ljava/util/function/Consumer;)V"), require = 1)
    private void luxloader$bindOwnedChannel(ChannelAccess.ChannelHandle channel, Consumer<com.mojang.blaze3d.audio.Channel> action,
                                            Operation<Void> original, @Local(argsOnly = true) SoundInstance sound) {
        if (sound instanceof ManagedSoundInstance managed) managed.bindChannel(channel);
        original.call(channel, action);
    }

    @WrapOperation(method = "play", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;thenAccept(Ljava/util/function/Consumer;)Ljava/util/concurrent/CompletableFuture;"), require = 2)
    private CompletableFuture<Void> luxloader$guardOwnedDecoded(CompletableFuture<?> decoded, Consumer<?> consumer,
            Operation<CompletableFuture<Void>> original, @Local(argsOnly = true) SoundInstance sound,
            @Local ChannelAccess.ChannelHandle channel) {
        return sound instanceof ManagedSoundInstance managed ? managed.consumeDecoded(decoded, channel) : original.call(decoded, consumer);
    }

    @WrapOperation(method = "play", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/Identifier;Z)Ljava/util/concurrent/CompletableFuture;"), require = 1)
    private CompletableFuture<AudioStream> luxloader$pluginEncodedStream(SoundBufferLibrary library, Identifier path, boolean looping,
            Operation<CompletableFuture<AudioStream>> original, @Local(argsOnly = true) SoundInstance sound) {
        return sound instanceof ManagedSoundInstance managed && managed.hasPluginAsset()
                ? managed.pluginStream(looping) : original.call(library, path, looping);
    }
}
