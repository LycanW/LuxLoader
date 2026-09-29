package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.ClientBehaviorHooks;
import dev.luxloader.mc.hooks.ClientBehaviorSignal;
import dev.luxloader.mc.hooks.RenderHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures server-originated facts only after packet application returned on the client thread. */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class ClientPacketBehaviorMixin {
    @Inject(method = "handleDamageEvent", at = @At("TAIL"), require = 1)
    private void luxloader$captureDamageNotification(@org.spongepowered.asm.mixin.injection.Coerce Object packet,
                                                     CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.DAMAGE_NOTIFICATION, this, packet, null, null, null);
    }

    @Inject(method = "handleHurtAnimation", at = @At("TAIL"), require = 1)
    private void luxloader$captureHurtAnimation(@org.spongepowered.asm.mixin.injection.Coerce Object packet,
                                                CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.HURT_ANIMATION, this, packet, null, null, null);
    }

    @Inject(method = "handleSetHealth", at = @At("TAIL"), require = 1)
    private void luxloader$captureHealthUpdate(@org.spongepowered.asm.mixin.injection.Coerce Object packet,
                                               CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.HEALTH_UPDATE, this, packet, null, null, null);
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"), require = 1)
    private void luxloader$captureBlockUpdate(@org.spongepowered.asm.mixin.injection.Coerce Object packet,
                                              CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.BLOCK_UPDATE, this, packet, null, null, null);
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"), require = 1)
    private void luxloader$captureSectionBlockUpdate(@org.spongepowered.asm.mixin.injection.Coerce Object packet,
                                                     CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.SECTION_BLOCK_UPDATE,
                this, packet, null, null, null);
    }

    @Inject(method = "handleSetHeldSlot",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V",
                    shift = At.Shift.BEFORE), require = 1)
    private void luxloader$beginServerSlotChange(@org.spongepowered.asm.mixin.injection.Coerce Object packet,
                                                 CallbackInfo callbackInfo) {
        RenderHooks.beginServerHotbarSlotNotification();
    }

    @Inject(method = "handleSetHeldSlot",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V",
                    shift = At.Shift.AFTER), require = 1)
    private void luxloader$endServerSlotChange(@org.spongepowered.asm.mixin.injection.Coerce Object packet,
                                               CallbackInfo callbackInfo) {
        RenderHooks.endServerHotbarSlotNotification();
    }

    @Inject(method = "handleSetHeldSlot", at = @At("TAIL"), require = 1)
    private void luxloader$captureServerSlotChange(@org.spongepowered.asm.mixin.injection.Coerce Object packet,
                                                   CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.SERVER_HOTBAR_SLOT,
                this, packet, null, null, null);
    }
}
