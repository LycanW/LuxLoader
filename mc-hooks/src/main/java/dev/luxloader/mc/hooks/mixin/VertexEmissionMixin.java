package dev.luxloader.mc.hooks.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import dev.luxloader.mc.hooks.EmissionCapture;
import dev.luxloader.mc.hooks.SourceEmission;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BufferBuilder.class)
public class VertexEmissionMixin {
    @Shadow private int vertices;
    @Unique private float[] luxloader$light;

    @Inject(method = "beginVertex", at = @At("RETURN"), require = 1)
    private void luxloader$vertexEmission(CallbackInfoReturnable<Long> cir) {
        Float emission = EmissionCapture.CURRENT.get();
        if (emission == null) return;
        if (luxloader$light == null) luxloader$light = new float[Math.max(256, vertices)];
        else if (vertices > luxloader$light.length)
            luxloader$light = java.util.Arrays.copyOf(luxloader$light, Math.max(vertices, luxloader$light.length * 2));
        luxloader$light[vertices - 1] = emission;
    }

    @Inject(method = "build", at = @At("RETURN"), require = 1)
    private void luxloader$meshEmission(CallbackInfoReturnable<MeshData> cir) {
        MeshData mesh = cir.getReturnValue();
        if (mesh != null && luxloader$light != null)
            ((SourceEmission) mesh).luxloader$emission(java.util.Arrays.copyOf(luxloader$light, mesh.drawState().vertexCount()));
        luxloader$light = null;
    }
}
