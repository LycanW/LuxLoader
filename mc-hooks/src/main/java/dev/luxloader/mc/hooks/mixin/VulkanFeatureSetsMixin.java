package dev.luxloader.mc.hooks.mixin;

import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import dev.luxloader.api.vulkan.VulkanFeatureSetRequest;
import dev.luxloader.mc.hooks.RenderHooks;
import org.lwjgl.system.Struct;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Adds plugin-requested optional feature groups to Minecraft's device selection. */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets", remap = false)
public class VulkanFeatureSetsMixin {
    @Inject(method = "optionalFeatureSets", at = @At("RETURN"), cancellable = true, remap = false)
    private static void luxloader$offerPluginFeatures(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        List<VulkanFeatureSetRequest> requests = RenderHooks.requestedVulkanFeatureSets();
        if (requests.isEmpty()) return;
        Set<FeatureSet> merged = new LinkedHashSet<>(cir.getReturnValue());
        int added = 0;
        for (VulkanFeatureSetRequest request : requests) {
            try {
                Set<VulkanFeature> features = new LinkedHashSet<>();
                for (VulkanFeatureSetRequest.Feature feature : request.features()) {
                    Class<?> type = Class.forName(feature.structClass());
                    if (!Struct.class.isAssignableFrom(type)) {
                        throw new IllegalArgumentException("Not a Vulkan struct: " + type);
                    }
                    features.add(new VulkanFeature(pNextStruct(type), feature.field()));
                }
                merged.add(new FeatureSet("LuxLoader / " + request.name(),
                        request.extensions(), features, FeatureSet.Condition.IDENTITY_CONDITION));
                added++;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                RenderHooks.noteVulkanFeatureSetFailed(request.name(), e);
            }
        }
        if (added > 0) {
            cir.setReturnValue(merged);
            RenderHooks.noteVulkanFeatureSetsOffered(added);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static VulkanPNextStruct pNextStruct(Class<?> type) {
        return new VulkanPNextStruct((Class) type);
    }
}
