package dev.luxloader.api.vulkan;

import java.util.List;
import java.util.Set;

/**
 * Optional Vulkan device feature group requested before Minecraft creates its
 * VkDevice. Extensions and feature bits are enabled together only if the
 * physical device supports the entire group.
 */
public record VulkanFeatureSetRequest(String name, Set<String> extensions,
                                      List<Feature> features) {
    public record Feature(String structClass, String field) {
        public Feature {
            if (structClass == null || !structClass.startsWith("org.lwjgl.vulkan.VkPhysicalDevice")
                    || field == null || field.isBlank()) {
                throw new IllegalArgumentException("Invalid Vulkan device feature: "
                        + structClass + "." + field);
            }
        }
    }

    public VulkanFeatureSetRequest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Vulkan feature group name is required");
        }
        extensions = extensions == null ? Set.of() : Set.copyOf(extensions);
        features = features == null ? List.of() : List.copyOf(features);
        if (extensions.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Vulkan extension names must be nonblank");
        }
    }
}
