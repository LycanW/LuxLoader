package dev.luxloader.mc;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates mappings against a real Minecraft client JAR without starting the game. Set
 * luxloader.test.minecraftJar or LUXLOADER_MINECRAFT_JAR; skip when unavailable. Load classes without
 * initialization to avoid triggering native libraries while inspecting structure.
 */
class MinecraftBridgeRealJarTest {

    /** Client JAR path: system property first, then environment variable. */
    private static Path clientJar() {
        String fromProperty = System.getProperty("luxloader.test.minecraftJar", "");
        if (!fromProperty.isBlank()) {
            return Path.of(fromProperty);
        }
        String fromEnv = System.getenv("LUXLOADER_MINECRAFT_JAR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv);
        }
        return null;
    }

    /** Search common Minecraft installation directories for a 26.3 client JAR. */
    private static Path autoDetectClientJar() {
        List<Path> roots = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            roots.add(Path.of(appData, ".minecraft", "versions"));
        }
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            roots.add(Path.of(home, ".minecraft", "versions"));
            roots.add(Path.of(home, "Library", "Application Support", "minecraft", "versions"));
        }

        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.list(root)) {
                Optional<Path> found = stream
                        .filter(Files::isDirectory)
                        .filter(dir -> dir.getFileName().toString().contains("26.3"))
                        .flatMap(dir -> {
                            try {
                                return Files.list(dir)
                                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                                        .filter(p -> !p.getFileName().toString().contains("natives"));
                            } catch (Exception e) {
                                return java.util.stream.Stream.<Path>empty();
                            }
                        })
                        .filter(p -> {
                            try {
                                // Exclude small library JARs using the client's size threshold of 30 MB.
                                return Files.size(p) > 10L * 1024 * 1024;
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (Exception ignored) {
                // Try the next installation root.
            }
        }
        return null;
    }

    private static Path resolveJar() {
        Path explicit = clientJar();
        if (explicit != null && Files.isRegularFile(explicit)) {
            return explicit;
        }
        return autoDetectClientJar();
    }

    private static LuxMod testMod() {
        return LuxMod.builder(new GpuId("dev.luxloader.test", "real-jar"), "真实 jar 测试", "1.0.0")
                .license("MIT")
                .build();
    }

    @Test
    @DisplayName("All Hookpoints Resolve On Real Client")
    void allHookpointsResolveOnRealClient() {
        Path jar = resolveJar();
        Assumptions.assumeTrue(jar != null,
                "Minecraft client JAR not found. "
                        + "Set -Dluxloader.test.minecraftJar=<path>, "
                        + "or LUXLOADER_MINECRAFT_JAR.");

        System.out.println("[Real JAR test] Client: " + jar);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, MinecraftBridge.class.getClassLoader())) {

            // Confirm the expected client generation by locating renderpearl.
            Assumptions.assumeTrue(
                    canLoad(loader, "com.mojang.renderpearl.api.device.GpuDevice"),
                    "Missing com.mojang.renderpearl; this may not be a 26.3+ client");

            MinecraftBridge bridge = new MinecraftBridge(testMod(), loader, true);
            String report = bridge.probe();
            System.out.println(report);

            assertTrue(bridge.isDeviceAccessible(),
                    "Required GpuDevice mapping must resolve");
            assertTrue(bridge.isDeviceInfoAvailable(), "DeviceInfo must resolve");
            assertTrue(bridge.isPresentInterceptable(),
                    "blitFromTexture and present mappings must resolve");
            assertTrue(bridge.isSurfaceConfigureObservable(),
                    "configure must resolve for resolution-change tracking");
            assertTrue(bridge.isFrameGraphAvailable(),
                    "Minecraft 26.3 must include FrameGraphBuilder");
            assertTrue(bridge.isVulkanBackend(),
                    "VulkanBackend must be discoverable");

            // Report optional mapping failures for diagnosis without failing the test.
            List<String> failures = bridge.mapping().failures();
            if (!failures.isEmpty()) {
                System.out.println("[Real JAR test] Optional unresolved mapping: " + failures);
            }
        } catch (Exception e) {
            fail("Real-client probing failed: " + e, e);
        }
    }

    @Test
    @DisplayName("Key Signatures Match Expectations")
    void keySignaturesMatchExpectations() {
        Path jar = resolveJar();
        Assumptions.assumeTrue(jar != null, "Minecraft client JAR not found");

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, MinecraftBridge.class.getClassLoader())) {

            Assumptions.assumeTrue(
                    canLoad(loader, "com.mojang.renderpearl.api.device.GpuSurface"),
                    "The JAR does not contain renderpearl");

            // GpuSurface methods used by presentation interception.
            Class<?> surface = Class.forName("com.mojang.renderpearl.api.device.GpuSurface",
                    false, loader);
            assertNotNull(findMethod(surface, "acquireNextTexture"),
                    "GpuSurface must expose acquireNextTexture");
            Method blit = findMethod(surface, "blitFromTexture");
            assertNotNull(blit, "GpuSurface must expose blitFromTexture");
            assertEquals(2, blit.getParameterCount(),
                    "blitFromTexture must accept CommandEncoder and GpuTextureView");
            assertNotNull(findMethod(surface, "present"), "GpuSurface must expose present");
            assertNotNull(findMethod(surface, "configure"), "GpuSurface must expose configure");

            // GpuDevice resource creation and command recording entry points.
            Class<?> device = Class.forName("com.mojang.renderpearl.api.device.GpuDevice",
                    false, loader);
            assertNotNull(findMethod(device, "createCommandEncoder"),
                    "GpuDevice must expose createCommandEncoder");
            assertNotNull(findMethod(device, "createSurface"), "GpuDevice must expose createSurface");
            assertNotNull(findMethod(device, "createTexture"), "GpuDevice must expose createTexture");
            assertNotNull(findMethod(device, "createBuffer"), "GpuDevice must expose createBuffer");
            assertNotNull(findMethod(device, "getDeviceInfo"),
                    "GpuDevice must expose getDeviceInfo for capability discovery");

            // DeviceInfo backend and capability queries.
            Class<?> info = Class.forName("com.mojang.renderpearl.api.device.DeviceInfo",
                    false, loader);
            assertNotNull(findMethod(info, "backendName"),
                    "DeviceInfo must expose backendName for backend detection");
            assertNotNull(findMethod(info, "underlyingExtensions"),
                    "DeviceInfo must expose underlyingExtensions");
            assertNotNull(findMethod(info, "limits"), "DeviceInfo must expose limits");
            assertNotNull(findMethod(info, "features"), "DeviceInfo must expose features");

            // VulkanBackend availability query.
            Class<?> vulkan = Class.forName("com.mojang.renderpearl.backend.vulkan.VulkanBackend",
                    false, loader);
            assertNotNull(findMethod(vulkan, "checkBackendAvailable"),
                    "VulkanBackend must expose checkBackendAvailable");

            // Minecraft's frame graph.
            Class<?> frameGraph = Class.forName("com.mojang.blaze3d.framegraph.FrameGraphBuilder",
                    false, loader);
            assertNotNull(findMethod(frameGraph, "addPass"), "FrameGraphBuilder must expose addPass");
            assertNotNull(findMethod(frameGraph, "execute"), "FrameGraphBuilder must expose execute");

            System.out.println("[Real JAR test] Key signatures verified");
        } catch (Exception e) {
            fail("Signature verification failed: " + e, e);
        }
    }

    @Test
    @DisplayName("Capabilities Reflect Real Probe")
    void capabilitiesReflectRealProbe() {
        Path jar = resolveJar();
        Assumptions.assumeTrue(jar != null, "Minecraft client JAR not found");

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, MinecraftBridge.class.getClassLoader())) {

            Assumptions.assumeTrue(
                    canLoad(loader, "com.mojang.renderpearl.api.device.GpuDevice"),
                    "The JAR does not contain renderpearl");

            MinecraftBridge bridge = new MinecraftBridge(testMod(), loader, false);
            bridge.probe();

            List<CapabilityDescriptor> registered = new ArrayList<>();
            bridge.registerCapabilities(registered::add);

            CapabilityDescriptor vulkan = find(registered, MinecraftBridge.CAP_VULKAN);
            assertEquals(CapabilityLevel.NATIVE, vulkan.level(),
                    "The real 26.3 client must expose the Vulkan backend");
            assertTrue(vulkan.isUsable());

            CapabilityDescriptor present = find(registered, MinecraftBridge.CAP_PRESENT_INTERCEPT);
            assertTrue(present.isUsable(),
                    "The real client must support presentation interception");

            CapabilityDescriptor frameGraph = find(registered, MinecraftBridge.CAP_FRAME_GRAPH);
            assertTrue(frameGraph.isUsable(), "Report the game frame graph as available");
            assertTrue(frameGraph.detail().contains("FrameGraphBuilder"),
                    "Include the concrete class name in the detail: " + frameGraph.detail());

            CapabilityDescriptor info = find(registered, MinecraftBridge.CAP_DEVICE_INFO);
            assertTrue(info.isUsable(), "Report device information as queryable");
            assertTrue(info.detail().contains("扩展") || info.detail().contains("限制"),
                    "Describe the available queries: " + info.detail());

            // Every capability records its source.
            for (CapabilityDescriptor descriptor : registered) {
                assertFalse(descriptor.provider().isBlank());
            }
        } catch (Exception e) {
            fail("Capability registration verification failed: " + e, e);
        }
    }

    @Test
    @DisplayName("Jar Looks Like AClient")
    void jarLooksLikeAClient() {
        Path jar = resolveJar();
        Assumptions.assumeTrue(jar != null, "Minecraft client JAR not found");

        try {
            long size = Files.size(jar);
            assertTrue(size > 10L * 1024 * 1024,
                    "The client JAR must exceed 10 MB; actual: " + size + " bytes");

            try (URLClassLoader loader = new URLClassLoader(
                    new URL[]{jar.toUri().toURL()}, null)) {
                // Verify the unobfuscated class names used by 26.3.
                assertTrue(canLoad(loader, "com.mojang.blaze3d.systems.RenderSystem"),
                        "Expected unobfuscated com.mojang.blaze3d.systems.RenderSystem");
                assertTrue(canLoad(loader, "net.minecraft.client.Minecraft"),
                        "Expected net.minecraft.client.Minecraft");
            }
            System.out.println("[Real JAR test] JAR size: " + (size / 1024 / 1024) + " MB，"
                    + "Class names use unobfuscated mappings");
        } catch (Exception e) {
            fail("JAR inspection failed: " + e, e);
        }
    }

    // Helpers.

    /** Check class existence without triggering static initialization. */
    private static boolean canLoad(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }

    private static CapabilityDescriptor find(List<CapabilityDescriptor> list, String id) {
        return list.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Unregistered capability: " + id));
    }

    /** Print automatically discovered paths for manual diagnosis. */
    @Test
    @DisplayName("Report Detected Jar Path")
    void reportDetectedJarPath() {
        Path jar = resolveJar();
        if (jar == null) {
            System.out.println("[Real JAR test] No client JAR discovered automatically; "
                    + "set -Dluxloader.test.minecraftJar=<path>");
        } else {
            System.out.println("[Real JAR test] Automatically discovered: " + jar.toAbsolutePath());
        }
        // Diagnostic output only; no assertions.
        assertTrue(true);
    }
}
