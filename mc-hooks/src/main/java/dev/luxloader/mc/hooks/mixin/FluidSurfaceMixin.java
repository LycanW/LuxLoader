package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.MinecraftFluidSurfaces;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.resources.model.sprite.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FluidStateModelSet.class)
public class FluidSurfaceMixin {
    @Inject(method = "get", at = @At("RETURN"), require = 1)
    private void luxloader$recordFluidSurface(CallbackInfoReturnable<FluidModel> cir) {
        FluidModel model = cir.getReturnValue();
        record(model.stillMaterial()); record(model.flowingMaterial()); record(model.overlayMaterial());
    }
    private static void record(Material.Baked material) {
        if (material != null) MinecraftFluidSurfaces.register(material.sprite().contents().name().toString());
    }
}
