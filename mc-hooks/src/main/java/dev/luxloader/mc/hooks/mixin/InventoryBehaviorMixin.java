package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.ClientBehaviorHooks;
import dev.luxloader.mc.hooks.ClientBehaviorSignal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes local hotbar selection changes at the single authoritative inventory setter. */
@Mixin(targets = "net.minecraft.world.entity.player.Inventory")
abstract class InventoryBehaviorMixin {
    @Inject(method = "setSelectedSlot", at = @At("TAIL"), require = 1)
    private void luxloader$captureHotbarSelection(int selectedSlot, CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.HOTBAR_SLOT_SET,
                this, selectedSlot, null, null, null);
    }
}
