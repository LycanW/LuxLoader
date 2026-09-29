package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.ClientBehaviorHooks;
import dev.luxloader.mc.hooks.ClientBehaviorSignal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the final GUI screen after the game and loader opening hooks have applied their result. */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class GuiBehaviorMixin {
    @Inject(method = "setScreen", at = @At("TAIL"), require = 1)
    private void luxloader$captureScreenChange(@Coerce Object requestedScreen, CallbackInfo callbackInfo) {
        ClientBehaviorHooks.capture(ClientBehaviorSignal.Kind.SCREEN_SET,
                this, requestedScreen, null, null, null);
    }
}
