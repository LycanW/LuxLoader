package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.RenderHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Release plugin Vulkan objects before Minecraft destroys its allocator and device. Verified close
 * order is checkpoint extension, command encoder, VMA allocator, VkDevice, then instance; a HEAD
 * injection runs before all of them. JVM shutdown cleanup is too late and previously caused
 * VUID-vkDestroyDevice-device-05137 and native crashes on Fabric and NeoForge. Since this injection is
 * optional, RenderDriverImpl.close also avoids touching a borrowed device unless the release hook was
 * observed.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanDevice", remap = false)
public class HostVulkanDeviceMixin {

    @Inject(method = "close", at = @At("HEAD"), require = 0, remap = false)
    private void luxloader$beforeHostClose(CallbackInfo ci) {
        RenderHooks.onHostDeviceClosing();
    }
}
