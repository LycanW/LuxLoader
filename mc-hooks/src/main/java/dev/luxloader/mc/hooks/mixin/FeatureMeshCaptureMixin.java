package dev.luxloader.mc.hooks.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import dev.luxloader.mc.hooks.PreparedMeshCapture;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StagedVertexBuffer.Draw.class)
public class FeatureMeshCaptureMixin {
    @Inject(method = "append", at = @At("HEAD"), require = 1)
    private void luxloader$capture(MeshData mesh, CallbackInfo ci) {
        PreparedMeshCapture.append(this, mesh);
    }
}
