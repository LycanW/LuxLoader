package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.RenderHooks;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The active pipeline owns whether host shadow decals enter preparation at all. */
@Mixin(ShadowFeatureRenderer.class)
public class EntityShadowFeatureMixin {
    @Inject(method = "buildGroup", at = @At("HEAD"), cancellable = true, require = 1)
    private void luxloader$selectEntityShadows(CallbackInfo ci) {
        if (!RenderHooks.usesPreparedEntityShadows()) ci.cancel();
    }
}
