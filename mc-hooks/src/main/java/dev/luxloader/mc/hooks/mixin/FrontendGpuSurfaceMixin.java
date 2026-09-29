package dev.luxloader.mc.hooks.mixin;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.luxloader.mc.hooks.RenderHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frame boundary only. World passes are composed in LevelRendererMixin. */
@Mixin(targets = "com.mojang.renderpearl.frontend.FrontendGpuSurface", remap = false)
public class FrontendGpuSurfaceMixin {
    @Inject(method = "blitFromTexture", at = @At("HEAD"), require = 0, remap = false)
    private void luxloader$beforeBlit(CommandEncoder encoder, GpuTextureView source,
                                      CallbackInfo ci) {
        RenderHooks.onPresentBoundary(this);
    }

    @Inject(method = "blitFromTexture", at = @At("RETURN"), require = 0, remap = false)
    private void luxloader$afterBlit(CommandEncoder encoder, GpuTextureView source,
                                     CallbackInfo ci) {
        RenderHooks.onFrameBlitted();
    }

    @Inject(method = "present", at = @At("HEAD"), require = 0, remap = false)
    private void luxloader$beforePresent(CallbackInfo ci) {
        RenderHooks.onFrameEnd();
    }
}
