package dev.luxloader.mc.hooks.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.luxloader.mc.hooks.EmissionCapture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionCompiler.class)
public class SectionEmissionCaptureMixin {
    @WrapMethod(method = "compile")
    private SectionCompiler.Results luxloader$scopeEmission(SectionPos pos, RenderSectionRegion region,
            VertexSorting sorting, SectionBufferBuilderPack buffers, Operation<SectionCompiler.Results> original) {
        Float previous = EmissionCapture.CURRENT.get();
        EmissionCapture.CURRENT.set(0f);
        try { return original.call(pos, region, sorting, buffers); }
        finally {
            if (previous == null) EmissionCapture.CURRENT.remove();
            else EmissionCapture.CURRENT.set(previous);
        }
    }

    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"), require = 1)
    private BlockState luxloader$sourceLight(RenderSectionRegion region, BlockPos pos, Operation<BlockState> original) {
        BlockState state = original.call(region, pos);
        EmissionCapture.CURRENT.set(state.getLightEmission() / 15f);
        return state;
    }
}
