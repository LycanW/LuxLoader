package dev.luxloader.mc.hooks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.luxloader.api.pipeline.CelestialRotation;
import dev.luxloader.mc.hooks.PreparedSkyOrientation;
import net.minecraft.client.renderer.SkyRenderer;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SkyRenderer.class)
public class SkyRendererMixin {
    // This call follows the method's outer pushPose, so its pop restores the sky pose.
    @WrapOperation(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V",
            ordinal = 0), require = 1)
    private void luxloader$orientCelestials(PoseStack pose, Axis axis, float degrees, Operation<Void> original) {
        var rotation = PreparedSkyOrientation.current();
        if (!rotation.equals(CelestialRotation.IDENTITY)) {
            pose.rotate(new Quaternionf(rotation.x(), rotation.y(), rotation.z(), rotation.w()));
        }
        original.call(pose, axis, degrees);
    }
}
