package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.TextureAnimationCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimationState")
public class TextureAnimationMixin {
    @Inject(method = {"<init>", "drawToAtlas"}, at = @At("RETURN"), require = 1)
    private void luxloader$publishFrame(CallbackInfo ci) { TextureAnimationCapture.publish(this); }
}
