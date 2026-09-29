package dev.luxloader.core.vulkan;

import dev.luxloader.api.gpu.NativeVulkanContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VulkanBorrowedDeviceTest {
    @Test void borrowedRecordingUsesHostParentsAndDoesNotDestroyThem() {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        try (var owner = VulkanDevice.create("Borrowed native context regression", true, null)) {
            assertNotNull(owner);
            var probe = VulkanApi.borrowHostDevice(owner.nativeInstanceHandle(), owner.physicalDevice().address(),
                    owner.physicalDevice().getInstance().getCapabilities().apiVersion, Set.of());
            assertFalse(probe.ownsInstance());
            assertEquals(owner.nativeInstanceHandle(), probe.instance().address());
            assertEquals(owner.physicalDevice().address(), probe.physicalDevice().address());
            try (var borrowed = VulkanDevice.adopt(probe.instance(), owner.vkDevice().address(),
                    probe.physicalDevice().address(), owner.capabilities(), null)) {
                assertNotNull(borrowed);
                assertFalse(borrowed.isOwned());
                var observed = new AtomicReference<NativeVulkanContext>();
                var command = borrowed.commands().begin("borrowed-native-callback");
                command.recordNativeVulkan(observed::set).end();
                assertEquals(owner.nativeInstanceHandle(), observed.get().instance());
                assertEquals(owner.physicalDevice().address(), observed.get().physicalDevice());
                assertEquals(owner.vkDevice().address(), observed.get().device());
                assertNotEquals(0, observed.get().commandBuffer());
                borrowed.commands().flush(borrowed.graphicsQueue(), List.of(), List.of());
                borrowed.commands().waitIdleAll();
            }
            // Closing the borrowed wrapper must leave the owner's instance/device usable.
            assertEquals(owner.capabilities().deviceName(),
                    VulkanApi.readCapabilities(owner.physicalDevice().getInstance(), owner.physicalDevice()).deviceName());
            owner.commands().begin("owner-after-borrow-release").end();
            owner.commands().flush(owner.graphicsQueue(), List.of(), List.of());
            owner.commands().waitIdleAll();
        }
    }
}
