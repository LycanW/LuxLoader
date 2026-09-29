package dev.luxloader.mc.hooks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.luxloader.mc.MinecraftFrameProjection;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererProjectionMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 1)
    private void luxloader$clearProjection(CallbackInfo ci) { MinecraftFrameProjection.clear(); }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), require = 1)
    private GpuBufferSlice luxloader$captureProjection(ProjectionMatrixBuffer buffer, Matrix4f projection,
                                                      Operation<GpuBufferSlice> original) {
        MinecraftFrameProjection.publish(projection.get(new float[16]));
        return original.call(buffer, projection);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 1)
    private void luxloader$releaseProjection(CallbackInfo ci) { MinecraftFrameProjection.clear(); }
}
