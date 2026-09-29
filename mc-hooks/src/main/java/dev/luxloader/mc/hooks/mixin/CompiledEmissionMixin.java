package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.SectionEmission;
import dev.luxloader.mc.hooks.SourceEmission;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
public class CompiledEmissionMixin implements SectionEmission, dev.luxloader.mc.hooks.SectionResourceGeneration {
    @Unique private long luxloader$resourceGeneration = Long.MIN_VALUE;
    @Override public long luxloader$resourceGeneration() { return luxloader$resourceGeneration; }
    @Override public void luxloader$resourceGeneration(long generation) { luxloader$resourceGeneration = generation; }
    @Unique private final Map<ChunkSectionLayer, float[]> luxloader$emission = new EnumMap<>(ChunkSectionLayer.class);
    @Override public Map<ChunkSectionLayer, float[]> luxloader$emissionLayers() { return luxloader$emission; }

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void luxloader$copyEmission(TranslucencyPointOfView view, SectionCompiler.Results results,
            long startTime, CallbackInfo ci) {
        luxloader$resourceGeneration = ((dev.luxloader.mc.hooks.SectionResourceGeneration)(Object) results).luxloader$resourceGeneration();
        results.renderedLayers.forEach((layer, mesh) ->
                luxloader$emission.put(layer, ((SourceEmission) mesh).luxloader$emission()));
    }
}
