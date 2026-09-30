package dev.luxloader.mc.hooks.mixin;

import com.mojang.blaze3d.audio.Channel;
import dev.luxloader.mc.hooks.ManagedSoundChannel;
import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import java.util.function.Consumer;

@Mixin(ChannelAccess.ChannelHandle.class)
abstract class ManagedSoundChannelMixin implements ManagedSoundChannel {
    @Shadow private Channel channel;
    @Shadow @Final ChannelAccess this$0;

    @Override public void luxloader$consumeManaged(Consumer<Channel> available, Runnable retired) {
        ((SoundChannelAccessMixin)this$0).luxloader$soundExecutor().execute(() -> {
            if (channel == null) retired.run(); else available.accept(channel);
        });
    }
}
