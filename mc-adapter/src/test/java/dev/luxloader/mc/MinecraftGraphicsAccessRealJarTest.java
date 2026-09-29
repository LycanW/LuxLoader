package dev.luxloader.mc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates every device and texture access hop against the real client JAR, including private fields
 * and return types. Adapter behavior is tested separately with stubs in MinecraftHostAdapterTest.
 */
class MinecraftGraphicsAccessRealJarTest {

    private static Path resolveJar() {
        return MinecraftClientJar.resolve();
    }

    private static URLClassLoader loaderFor(Path jar) throws Exception {
        // Include .minecraft/libraries so reflective member enumeration can resolve referenced classes such as Brigadier.
        return MinecraftClientJar.classLoaderFor(jar);
    }

    private static boolean canLoad(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field field : safeFields(c)) {
                if (field.getName().equals(name)) {
                    return field;
                }
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : safeMethods(c)) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == parameters.length) {
                    return method;
                }
            }
        }
        return null;
    }

    /** Find a method without restricting its parameter count. */
    private static Method findAnyMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : safeMethods(c)) {
                if (method.getName().equals(name)) {
                    return method;
                }
            }
        }
        return null;
    }

    /**
     * Return no methods when missing signature dependencies cause NoClassDefFoundError, allowing callers
     * to report an unresolved mapping.
     */
    private static Method[] safeMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (LinkageError e) {
            return new Method[0];
        }
    }

    private static Field[] safeFields(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (LinkageError e) {
            return new Field[0];
        }
    }

    @Test
    @DisplayName("Device Chain Is Intact On Real Client")
    void deviceChainIsIntactOnRealClient() throws Exception {
        Path jar = resolveJar();
        Assumptions.assumeTrue(jar != null, "Minecraft client JAR not found");

        try (URLClassLoader loader = loaderFor(jar)) {
            Assumptions.assumeTrue(
                    canLoad(loader, "com.mojang.renderpearl.api.device.GpuSurface"),
                    "Missing renderpearl; this may not be a 26.3+ client");

            // Hop 1: private FrontendGpuSurface.backend field.
            Class<?> surface = Class.forName(
                    "com.mojang.renderpearl.frontend.FrontendGpuSurface", false, loader);
            Field backend = findField(surface, "backend");
            assertNotNull(backend, "FrontendGpuSurface must expose its backend field; "
                    + "use MinecraftGraphicsAccess overrides if this mapping changes");
            assertEquals("com.mojang.renderpearl.backend.api.GpuSurfaceBackend",
                    backend.getType().getName(),
                    "The backend field type changed; update the following device hop");

            // Hop 2: private VulkanGpuSurface.device field.
            Class<?> vulkanSurface = Class.forName(
                    "com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface", false, loader);
            Field device = findField(vulkanSurface, "device");
            assertNotNull(device, "VulkanGpuSurface must have a device field");
            assertEquals("com.mojang.renderpearl.backend.vulkan.VulkanDevice",
                    device.getType().getName(), "The device field type changed");

            // Hop 3: public VulkanDevice.vkDevice() method.
            Class<?> vulkanDevice = Class.forName(
                    "com.mojang.renderpearl.backend.vulkan.VulkanDevice", false, loader);
            Method vkDevice = findMethod(vulkanDevice, "vkDevice");
            assertNotNull(vkDevice, "VulkanDevice must expose vkDevice()");
            assertEquals("org.lwjgl.vulkan.VkDevice", vkDevice.getReturnType().getName(),
                    "Return an LWJGL wrapper exposing address()");

            // Hop 4: VulkanDevice.instance() followed by VulkanInstance.vkInstance().
            Method instance = findMethod(vulkanDevice, "instance");
            assertNotNull(instance, "VulkanDevice must expose instance()");
            assertEquals("com.mojang.renderpearl.backend.vulkan.VulkanInstance",
                    instance.getReturnType().getName(), "The instance() return type changed");

            Class<?> vulkanInstance = Class.forName(
                    "com.mojang.renderpearl.backend.vulkan.VulkanInstance", false, loader);
            Method vkInstance = findMethod(vulkanInstance, "vkInstance");
            assertNotNull(vkInstance, "VulkanInstance must expose vkInstance(); "
                    + "the core needs its handle to enumerate physical devices");
            assertEquals("org.lwjgl.vulkan.VkInstance", vkInstance.getReturnType().getName());

            // Hop 5: DeviceInfo capability snapshot.
            Method getDeviceInfo = findMethod(vulkanDevice, "getDeviceInfo");
            assertNotNull(getDeviceInfo, "VulkanDevice must expose getDeviceInfo()");

            System.out.println("[Real JAR] Verified all five device access hops");
        }
    }

    @Test
    @DisplayName("Texture Unwrap Chain Is Intact")
    void textureUnwrapChainIsIntact() throws Exception {
        Path jar = resolveJar();
        Assumptions.assumeTrue(jar != null, "Minecraft client JAR not found");

        try (URLClassLoader loader = loaderFor(jar)) {
            Assumptions.assumeTrue(canLoad(loader, "com.mojang.renderpearl.api.device.GpuSurface"),
                    "The JAR does not contain renderpearl");

            // blitFromTexture parameter types.
            Class<?> surface = Class.forName("com.mojang.renderpearl.api.device.GpuSurface",
                    false, loader);
            Method blit = findAnyMethod(surface, "blitFromTexture");
            assertNotNull(blit, "GpuSurface must expose blitFromTexture");
            assertEquals("com.mojang.renderpearl.api.textures.GpuTextureView",
                    blit.getParameterTypes()[1].getName(),
                    "The second parameter must be GpuTextureView for VkImage extraction");

            // View-to-texture accessor belongs to the base class, not the interface.
            Class<?> baseView = Class.forName(
                    "com.mojang.renderpearl.backend.common.BaseGpuTextureView", false, loader);
            Method texture = findMethod(baseView, "texture");
            assertNotNull(texture, "BaseGpuTextureView must expose texture()");
            assertEquals("com.mojang.renderpearl.api.textures.GpuTexture",
                    texture.getReturnType().getName());

            // Read actual view dimensions rather than guessing the resolution.
            Method getWidth = findMethod(baseView, "getWidth", int.class);
            Method getHeight = findMethod(baseView, "getHeight", int.class);
            assertNotNull(getWidth, "BaseGpuTextureView must expose getWidth(int)");
            assertNotNull(getHeight, "BaseGpuTextureView must expose getHeight(int)");

            // Texture-to-VkImage access.
            Class<?> vulkanTexture = Class.forName(
                    "com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture", false, loader);
            Method vkImage = findMethod(vulkanTexture, "vkImage");
            assertNotNull(vkImage, "VulkanGpuTexture must expose vkImage()");
            assertEquals(long.class, vkImage.getReturnType(),
                    "vkImage() must return a raw long handle");

            // The view's VkImageView handle.
            Class<?> vulkanView = Class.forName(
                    "com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView", false, loader);
            Method vkImageView = findMethod(vulkanView, "vkImageView");
            assertNotNull(vkImageView, "VulkanGpuTextureView must expose vkImageView()");
            assertEquals(long.class, vkImageView.getReturnType());

            System.out.println("[Real JAR] Texture access chain verified");
        }
    }

    @Test
    @DisplayName("Vulkan Device Does Not Expose Physical Device")
    void vulkanDeviceDoesNotExposePhysicalDevice() throws Exception {
        Path jar = resolveJar();
        Assumptions.assumeTrue(jar != null, "Minecraft client JAR not found");

        try (URLClassLoader loader = loaderFor(jar)) {
            Assumptions.assumeTrue(canLoad(loader, "com.mojang.renderpearl.api.device.GpuSurface"),
                    "The JAR does not contain renderpearl");

            Class<?> vulkanDevice = Class.forName(
                    "com.mojang.renderpearl.backend.vulkan.VulkanDevice", false, loader);

            // If a future version adds an accessor, this assertion prompts replacing private-field access.
            List<String> accessors = new ArrayList<>();
            for (Method method : safeMethods(vulkanDevice)) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("physical") || name.contains("vkphysical")) {
                    accessors.add(method.getName());
                }
            }
            List<String> fields = new ArrayList<>();
            for (Field field : safeFields(vulkanDevice)) {
                if (field.getName().toLowerCase(java.util.Locale.ROOT).contains("physical")) {
                    fields.add(field.getName());
                }
            }

            assertTrue(accessors.isEmpty() && fields.isEmpty(),
                    "VulkanDevice now exposes its physical device (method=" + accessors + ", field=" + fields
                            + "). Use the host-provided handle "
                            + "to populate HostDevice.physicalDevice and avoid fallback enumeration.");

            System.out.println("[Real JAR] VulkanDevice retains no physical-device handle; "
                    + "instance-based enumeration remains necessary");
        }
    }

    @Test
    @DisplayName("New Hookpoints Resolve")
    void newHookpointsResolve() throws Exception {
        Path jar = resolveJar();
        Assumptions.assumeTrue(jar != null, "Minecraft client JAR not found");

        try (URLClassLoader loader = loaderFor(jar)) {
            Assumptions.assumeTrue(canLoad(loader, "com.mojang.renderpearl.api.device.GpuSurface"),
                    "The JAR does not contain renderpearl");

            MinecraftMapping mapping = new MinecraftMapping();
            List<String> unresolved = new ArrayList<>();

            for (MinecraftMapping.Hookpoint hookpoint : MinecraftMapping.Hookpoint.values()) {
                Optional<MinecraftMapping.ResolvedHook> resolved = mapping.resolve(hookpoint, loader);
                if (resolved.isEmpty()) {
                    unresolved.add(hookpoint.name());
                    continue;
                }
                MinecraftMapping.ResolvedHook hook = resolved.get();
                assertTrue(hook.isBindablePresent(),
                        hookpoint + " resolved to " + hook.target() + " but its member is missing; "
                                + "the hook is only partially resolved");
            }

            assertTrue(unresolved.isEmpty(),
                    "Unresolved mappings in the real client: " + unresolved
                            + "; failure details: " + mapping.failures());

            // The four required device-chain field bindings must resolve to real fields.
            for (MinecraftMapping.Hookpoint hookpoint : List.of(
                    MinecraftMapping.Hookpoint.SURFACE_BACKEND,
                    MinecraftMapping.Hookpoint.SURFACE_BACKEND_DEVICE)) {
                MinecraftMapping.ResolvedHook hook = mapping.resolve(hookpoint, loader).orElseThrow();
                assertTrue(hook.isFieldPresent(),
                        hookpoint + " binds a missing field: " + hook.target());
            }

            System.out.println(mapping.describe());
        } catch (Exception e) {
            fail("New mapping verification failed: " + e, e);
        }
    }

    @Test
    @DisplayName("Null Surface Yields No Adapter")
    void nullSurfaceYieldsNoAdapter() {
        assertTrue(MinecraftHostAdapter.create(null, getClass().getClassLoader(),
                "Fabric", "26.3", java.util.Map.of(), false) == null);
    }

    @Test
    @DisplayName("Open Gl Surface Degrades Quietly")
    void openGlSurfaceDegradesQuietly() {
        // Any unmapped object makes the first hop fail.
        Object notASurface = new Object();

        MinecraftHostAdapter adapter = MinecraftHostAdapter.create(notASurface,
                getClass().getClassLoader(), "Fabric", "26.3", java.util.Map.of(), false);

        assertNotNull(adapter, "Create the adapter even without a device so unavailable capabilities can be reported");
        assertTrue(adapter.device() == null, "device() must return null when unavailable");
        assertTrue(adapter.capabilities() == null);
        assertFalse(adapter.supportsSceneExtraction());
        assertEquals(dev.luxloader.api.host.HostAdapter.HostFrameTextures.EMPTY,
                adapter.frameTextures(), "Return EMPTY before frame capture");

        List<dev.luxloader.api.capability.CapabilityDescriptor> registered = new ArrayList<>();
        adapter.registerCapabilities(registered::add);
        assertTrue(registered.stream().anyMatch(d ->
                        d.id().equals(dev.luxloader.api.capability.CapabilityDescriptor.Ids.VULKAN_BACKEND)
                                && !d.isUsable()),
                "Report the Vulkan backend unavailable: " + registered);
        assertFalse(adapter.describe().isBlank());
    }
}
