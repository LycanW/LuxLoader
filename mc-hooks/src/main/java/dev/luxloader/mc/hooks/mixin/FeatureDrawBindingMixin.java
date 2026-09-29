package dev.luxloader.mc.hooks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.luxloader.mc.hooks.PreparedMeshCapture;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public class FeatureDrawBindingMixin {
    @Unique private PreparedRenderType luxloader$prepared;

    @WrapOperation(method = "getOrAddDraw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/rendertype/RenderType;prepare()Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;"), require = 1)
    private PreparedRenderType luxloader$preparedBinding(RenderType type, Operation<PreparedRenderType> original) {
        luxloader$prepared = original.call(type);
        return luxloader$prepared;
    }

    @Inject(method = "getOrAddDraw", at = @At("RETURN"), require = 1)
    private void luxloader$bind(RenderType type, CallbackInfoReturnable<StagedVertexBuffer.Draw> cir) {
        PreparedMeshCapture.bind(cir.getReturnValue(), type, luxloader$prepared);
    }
}
