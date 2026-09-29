package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.MinecraftResourceAccess;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ReloadableResourceManager.class)
public class ResourceReloadMixin {
    @Inject(method = "createReload", at = @At("RETURN"), require = 1)
    private void luxloader$invalidateResources(CallbackInfoReturnable<?> cir) {
        MinecraftResourceAccess.reloaded();
        dev.luxloader.mc.MinecraftFluidSurfaces.clear();
        ((net.minecraft.server.packs.resources.ReloadInstance) cir.getReturnValue()).done()
                .thenRun(MinecraftResourceAccess::contentsReady);
    }
}
