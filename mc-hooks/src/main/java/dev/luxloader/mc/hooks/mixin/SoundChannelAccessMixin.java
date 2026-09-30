package dev.luxloader.mc.hooks.mixin;

import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.concurrent.Executor;

@Mixin(ChannelAccess.class)
public interface SoundChannelAccessMixin {
    @Accessor("executor") Executor luxloader$soundExecutor();
}
