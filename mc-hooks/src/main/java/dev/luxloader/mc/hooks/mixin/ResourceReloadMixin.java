package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.MinecraftResourceAccess;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ReloadableResourceManager.class)
public class ResourceReloadMixin {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    private net.minecraft.server.packs.PackType type;
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "createReload")
    private net.minecraft.server.packs.resources.ReloadInstance luxloader$trackResources(
            java.util.concurrent.Executor preparationExecutor, java.util.concurrent.Executor reloadExecutor,
            java.util.concurrent.CompletableFuture<net.minecraft.util.Unit> initialStage,
            java.util.List<net.minecraft.server.packs.PackResources> packs,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.server.packs.resources.ReloadInstance> original) {
        if (type != net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
            return original.call(preparationExecutor, reloadExecutor, initialStage, packs);
        dev.luxloader.mc.MinecraftFluidSurfaces.clear();
        return MinecraftResourceAccess.trackReload(
                () -> original.call(preparationExecutor, reloadExecutor, initialStage, packs),
                net.minecraft.server.packs.resources.ReloadInstance::done);
    }

    @Inject(method = "close", at = @At("HEAD"), require = 1)
    private void luxloader$closeResources(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (type == net.minecraft.server.packs.PackType.CLIENT_RESOURCES) MinecraftResourceAccess.closed();
    }
}
