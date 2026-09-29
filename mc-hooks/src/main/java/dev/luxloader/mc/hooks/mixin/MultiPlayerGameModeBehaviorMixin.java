package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.ClientBehaviorHooks;
import dev.luxloader.mc.hooks.ClientBehaviorSignal;
import dev.luxloader.mc.hooks.RenderHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures actual local game-mode action entrances and their separate client results. */
@Mixin(targets = "net.minecraft.client.multiplayer.MultiPlayerGameMode")
abstract class MultiPlayerGameModeBehaviorMixin {
    @Inject(method = "attack", at = @At("HEAD"), require = 1)
    private void luxloader$captureAttack(@Coerce Object player, @Coerce Object target, CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.ATTACK, this, player, target, null, null);
    }

    @Inject(method = "useItem", at = @At("HEAD"), require = 1)
    private void luxloader$captureItemUseRequest(@Coerce Object player, @Coerce Object hand,
                                                 CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.ITEM_USE_REQUEST, this, player, hand, null, null);
    }

    @Inject(method = "useItem", at = @At("RETURN"), require = 1)
    private void luxloader$captureItemUseResult(@Coerce Object player, @Coerce Object hand,
                                                CallbackInfoReturnable<?> callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.ITEM_USE_RESULT, this, player, hand,
                null, callbackInfo.getReturnValue());
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), require = 1)
    private void luxloader$captureBlockUseRequest(@Coerce Object player, @Coerce Object hand,
                                                  @Coerce Object hitResult, CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.ITEM_USE_ON_BLOCK_REQUEST,
                this, player, hand, hitResult, null);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"), require = 1)
    private void luxloader$captureBlockUseResult(@Coerce Object player, @Coerce Object hand,
                                                 @Coerce Object hitResult, CallbackInfoReturnable<?> callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.ITEM_USE_ON_BLOCK_RESULT,
                this, player, hand, hitResult, callbackInfo.getReturnValue());
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), require = 1)
    private void luxloader$captureBreakStart(@Coerce Object position, @Coerce Object direction,
                                             CallbackInfoReturnable<Boolean> callbackInfo) {
        ClientBehaviorSignal.Kind kind = RenderHooks.isContinuingBlockBreak()
                ? ClientBehaviorSignal.Kind.BREAK_START_NESTED_REQUEST
                : ClientBehaviorSignal.Kind.BREAK_START_REQUEST;
        ClientBehaviorHooks.capture(kind,
                this, position, direction, null, null);
    }

    @Inject(method = "startDestroyBlock", at = @At("RETURN"), require = 1)
    private void luxloader$captureBreakStartResult(@Coerce Object position, @Coerce Object direction,
                                                   CallbackInfoReturnable<Boolean> callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.BREAK_START_RESULT,
                this, position, direction, null, callbackInfo.getReturnValue());
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), require = 1)
    private void luxloader$captureBreakContinue(@Coerce Object position, @Coerce Object direction,
                                                CallbackInfoReturnable<Boolean> callbackInfo) {
        RenderHooks.beginContinueBlockBreak();
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.BREAK_CONTINUE,
                this, position, direction, null, null);
    }

    @Inject(method = "continueDestroyBlock", at = @At("RETURN"), require = 1)
    private void luxloader$endBreakContinue(CallbackInfoReturnable<Boolean> callbackInfo) {
        RenderHooks.endContinueBlockBreak();
    }

    @Inject(method = "stopDestroyBlock", at = @At("HEAD"), require = 1)
    private void luxloader$captureBreakStop(CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.BREAK_STOP, this, null, null, null, null);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"), require = 1)
    private void luxloader$capturePredictedBreak(@Coerce Object position,
                                                 CallbackInfoReturnable<Boolean> callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.BREAK_PREDICTION,
                this, position, null, null, callbackInfo.getReturnValue());
    }
}
