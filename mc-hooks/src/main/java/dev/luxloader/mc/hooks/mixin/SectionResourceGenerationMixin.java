package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.SectionResourceGeneration;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionCompiler.Results.class)
public class SectionResourceGenerationMixin implements SectionResourceGeneration {
    @Unique private long luxloader$resourceGeneration = Long.MIN_VALUE;
    @Override public long luxloader$resourceGeneration() { return luxloader$resourceGeneration; }
    @Override public void luxloader$resourceGeneration(long generation) { luxloader$resourceGeneration = generation; }
}
