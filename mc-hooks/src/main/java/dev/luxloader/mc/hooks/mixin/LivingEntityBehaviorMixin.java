package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.ClientBehaviorHooks;
import dev.luxloader.mc.hooks.ClientBehaviorSignal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the local jump method entry before its short-lived impulse can disappear. */
@Mixin(targets = "net.minecraft.world.entity.LivingEntity")
abstract class LivingEntityBehaviorMixin {
    @Inject(method = "jumpFromGround", at = @At("HEAD"), require = 1)
    private void luxloader$captureJump(CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.JUMP, this, null, null, null, null);
    }
}
