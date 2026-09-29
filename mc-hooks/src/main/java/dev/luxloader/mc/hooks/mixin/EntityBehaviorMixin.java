package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.ClientBehaviorHooks;
import dev.luxloader.mc.hooks.ClientBehaviorSignal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures local-player movement transitions from the actual entity update methods. */
@Mixin(targets = "net.minecraft.world.entity.Entity")
abstract class EntityBehaviorMixin {
    @Inject(method = "setOnGround", at = @At("TAIL"), require = 1)
    private void luxloader$captureGroundAssignment(boolean onGround, CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.GROUND_STATE,
                this, onGround, null, null, null);
    }

    @Inject(method = "setOnGroundWithMovement(ZZLnet/minecraft/world/phys/Vec3;)V",
            at = @At("TAIL"), require = 1)
    private void luxloader$captureGroundMovement(boolean onGround, boolean horizontalCollision,
                                                 @Coerce Object movement, CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.GROUND_STATE,
                this, onGround, null, null, null);
    }

    @Inject(method = "updateFluidInteraction", at = @At("HEAD"), require = 1)
    private void luxloader$captureFluidUpdateStart(CallbackInfoReturnable<Boolean> callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.FLUID_UPDATE_START, this, null, null, null, null);
    }

    @Inject(method = "updateFluidInteraction", at = @At("RETURN"), require = 1)
    private void luxloader$captureFluidUpdateEnd(CallbackInfoReturnable<Boolean> callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.FLUID_UPDATE_END, this, null, null, null, null);
    }
}
