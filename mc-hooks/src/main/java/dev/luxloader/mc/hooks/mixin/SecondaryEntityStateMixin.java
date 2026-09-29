package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.SecondaryEntityStates;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderState.class)
public class SecondaryEntityStateMixin implements SecondaryEntityStates {
    @Unique private final List<SecondaryEntityStates.Entry> luxloader$secondary = new ArrayList<>();
    @Unique private final List<net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> luxloader$secondaryBlocks = new ArrayList<>();

    @Override public List<SecondaryEntityStates.Entry> luxloader$secondaryEntities() { return luxloader$secondary; }
    @Override public List<net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> luxloader$secondaryBlockEntities() {
        return luxloader$secondaryBlocks;
    }

    @Inject(method = "reset", at = @At("HEAD"), require = 1)
    private void luxloader$clearSecondary(CallbackInfo ci) { luxloader$secondary.clear(); luxloader$secondaryBlocks.clear(); }
}
