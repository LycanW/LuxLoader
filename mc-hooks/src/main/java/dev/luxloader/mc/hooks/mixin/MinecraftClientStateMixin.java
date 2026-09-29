package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.RenderHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures after each simulation tick and drains only after Minecraft's complete client-loop work. */
@Mixin(targets = "net.minecraft.client.Minecraft")
abstract class MinecraftClientStateMixin {
    @Inject(method = "tick", at = @At("TAIL"), require = 1)
    private void luxloader$captureClientTick(CallbackInfo callbackInfo) {
        RenderHooks.onClientTick(this);
    }

    @Inject(method = "runTick", at = @At("RETURN"), require = 1)
    private void luxloader$dispatchClientStateAtSafePoint(boolean shouldTick, CallbackInfo callbackInfo) {
        RenderHooks.onClientSafePoint(this);
    }
}
