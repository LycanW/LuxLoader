package dev.luxloader.api.gpu;

/**
 * Borrowed Vulkan handles valid only during a {@link GpuCommands.CommandBuffer}
 * native recording callback. The command buffer is already recording and must
 * not be ended or submitted by the plugin. The plugin retains ownership of any
 * native objects it creates and must release them before pipeline shutdown.
 */
public record NativeVulkanContext(long instance, long physicalDevice, long device,
                                  long commandBuffer, int queueFamilyIndex) {
    public NativeVulkanContext {
        if (instance == 0L || physicalDevice == 0L || device == 0L
                || commandBuffer == 0L || queueFamilyIndex < 0) {
            throw new IllegalArgumentException("Incomplete native Vulkan recording context: instance=0x"
                    + Long.toHexString(instance) + ", physicalDevice=0x" + Long.toHexString(physicalDevice)
                    + ", device=0x" + Long.toHexString(device) + ", commandBuffer=0x"
                    + Long.toHexString(commandBuffer) + ", queueFamilyIndex=" + queueFamilyIndex);
        }
    }
}
