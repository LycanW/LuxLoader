package dev.luxloader.mc.hooks.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import dev.luxloader.mc.hooks.SourceEmission;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MeshData.class)
public class MeshEmissionMixin implements SourceEmission {
    @Unique private float[] luxloader$emission = new float[0];
    @Override public float[] luxloader$emission() { return luxloader$emission; }
    @Override public void luxloader$emission(float[] values) { luxloader$emission = values; }
}
