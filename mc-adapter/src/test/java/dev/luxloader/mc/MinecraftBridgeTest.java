package dev.luxloader.mc;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests graceful fallback when Minecraft mappings are unavailable. Missing or changed classes must
 * produce an unavailable result with a reason instead of preventing startup. Real-client mapping
 * checks live in MinecraftBridgeRealJarTest.
 */
class MinecraftBridgeTest {

    private static LuxMod testMod() {
        return LuxMod.builder(new GpuId("dev.luxloader.test", "bridge-test"), "测试", "1.0.0")
                .license("MIT")
                .build();
    }

    /** Use an isolated class loader to simulate absent Minecraft classes. */
    private static MinecraftBridge bridgeWithoutMinecraft(boolean verbose) {
        ClassLoader empty = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                throw new ClassNotFoundException(name);
            }
        };
        // A null parent excludes application classes while bootstrap classes remain available.
        return new MinecraftBridge(testMod(), empty, verbose);
    }

    @Test
    @DisplayName("Probe Does Not Throw Without Minecraft")
    void probeDoesNotThrowWithoutMinecraft() {
        MinecraftBridge bridge = bridgeWithoutMinecraft(false);

        String report = assertDoesNotThrow(bridge::probe,
                "Probe failures must not throw when Minecraft is absent");

        assertNotNull(report);
        assertFalse(report.isBlank());
        assertTrue(bridge.isProbed());

        assertFalse(bridge.isVulkanBackend(), "Without Minecraft, Vulkan integration must be unavailable");
        assertFalse(bridge.isDeviceAccessible(), "Without Minecraft, device access must be unavailable");
        assertFalse(bridge.isPresentInterceptable(), "Without Minecraft, presentation interception must be unavailable");
        assertFalse(bridge.isFrameGraphAvailable());
        assertFalse(bridge.isDeviceInfoAvailable());
        assertFalse(bridge.isSurfaceConfigureObservable());
    }

    @Test
    @DisplayName("Probe Lists Attempted Candidates")
    void probeListsAttemptedCandidates() {
        MinecraftBridge bridge = bridgeWithoutMinecraft(false);
        bridge.probe();

        List<String> failures = bridge.mapping().failures();
        assertFalse(failures.isEmpty(), "Missing mappings must record failure reasons");

        boolean hasCandidates = failures.stream().anyMatch(f -> f.contains("已尝试"));
        assertTrue(hasCandidates, "Failure details must list attempted candidates: " + failures);

        String description = bridge.mapping().describe();
        assertTrue(description.contains("未解析"), description);
        assertTrue(description.contains("overrides"),
                "Diagnostics must mention manual overrides: " + description);
        // Mark required mappings so fatal failures are distinguishable from optional ones.
        assertTrue(description.contains("[必需]"),
                "Identify required mappings: " + description);
    }

    @Test
    @DisplayName("Overrides Are Honored")
    void overridesAreHonored() {
        MinecraftBridge bridge = new MinecraftBridge(testMod(), true);

        // Override the mapping with the JDK String class, which always exists.
        bridge.override(MinecraftMapping.Hookpoint.GPU_DEVICE, "java.lang.String#length");

        Optional<MinecraftMapping.ResolvedHook> resolved =
                bridge.mapping().resolve(MinecraftMapping.Hookpoint.GPU_DEVICE);

        assertTrue(resolved.isPresent(), "An override pointing to an existing class must resolve");
        assertEquals(String.class, resolved.get().type());
        assertEquals("length", resolved.get().methodName());
        assertTrue(resolved.get().hasMethod());
        assertTrue(resolved.get().isMethodPresent(), "The length method must exist");

        Optional<java.lang.reflect.Method> method = resolved.get().findMethod();
        assertTrue(method.isPresent());
        assertEquals("length", method.get().getName());
    }

    @Test
    @DisplayName("Missing Method Is Detected As Half Broken")
    void missingMethodIsDetectedAsHalfBroken() {
        MinecraftBridge bridge = new MinecraftBridge(testMod(), true);

        // String exists but has no noSuchMethod, simulating a renamed method.
        bridge.override(MinecraftMapping.Hookpoint.PRESENT, "java.lang.String#noSuchMethodAtAll");

        Optional<MinecraftMapping.ResolvedHook> resolved =
                bridge.mapping().resolve(MinecraftMapping.Hookpoint.PRESENT);
        assertTrue(resolved.isPresent(), "An existing class must produce a resolved hook");
        assertFalse(resolved.get().isMethodPresent(),
                "Missing methods must mark the hook incomplete before runtime use");

        // The probe must report this mapping as unavailable.
        bridge.probe();
        assertFalse(bridge.isPresentInterceptable(),
                "Incomplete hooks must not count as available");
    }

    @Test
    @DisplayName("Invalid Override Falls Back")
    void invalidOverrideFallsBack() {
        MinecraftBridge bridge = bridgeWithoutMinecraft(false);
        bridge.override(MinecraftMapping.Hookpoint.DEVICE_INFO,
                "com.example.NoSuchClass#noSuchMethod");

        bridge.mapping().resolve(MinecraftMapping.Hookpoint.DEVICE_INFO);

        assertTrue(bridge.mapping().failures().stream()
                        .anyMatch(f -> f.contains("用户覆盖")),
                "Record an unloadable override: " + bridge.mapping().failures());
    }

    @Test
    @DisplayName("Capabilities Report Unavailable")
    void capabilitiesReportUnavailable() {
        MinecraftBridge bridge = bridgeWithoutMinecraft(false);
        bridge.probe();

        List<CapabilityDescriptor> registered = new ArrayList<>();
        bridge.registerCapabilities(registered::add);

        assertEquals(6, registered.size(), "Register six capabilities");

        for (CapabilityDescriptor descriptor : registered) {
            assertFalse(descriptor.isUsable(),
                    "Without Minecraft, capability " + descriptor.id() + " must be unavailable");
            assertFalse(descriptor.detail().isBlank(),
                    "Capability " + descriptor.id() + " must explain its unavailability");
            assertFalse(descriptor.provider().isBlank(),
                    "Capability " + descriptor.id() + " has no provider");
        }

        CapabilityDescriptor vulkan = find(registered, MinecraftBridge.CAP_VULKAN);
        assertEquals(CapabilityLevel.UNSUPPORTED, vulkan.level());

        CapabilityDescriptor present = find(registered, MinecraftBridge.CAP_PRESENT_INTERCEPT);
        assertTrue(present.detail().contains("必要条件") || present.detail().contains("无法接管"),
                "Describe the affected functionality: " + present.detail());
    }

    @Test
    @DisplayName("Hookpoints Have Candidates And Purpose")
    void hookpointsHaveCandidatesAndPurpose() {
        for (MinecraftMapping.Hookpoint hookpoint : MinecraftMapping.Hookpoint.values()) {
            assertFalse(hookpoint.candidates().isEmpty(), hookpoint + " must have candidate targets");
            assertFalse(hookpoint.displayName().isBlank());
            assertFalse(hookpoint.purpose().isBlank(),
                    hookpoint + " must describe its purpose for mapping calibration");
            for (String candidate : hookpoint.candidates()) {
                assertTrue(candidate.contains("."),
                        "Candidates must use fully qualified class or class#method names: " + candidate);
            }
        }
    }

    @Test
    @DisplayName("All Critical Hookpoints Exist")
    void allCriticalHookpointsExist() {
        // Minimum mappings required for frame takeover.
        assertNotNull(MinecraftMapping.Hookpoint.GPU_DEVICE);
        assertNotNull(MinecraftMapping.Hookpoint.DEVICE_INFO);
        assertNotNull(MinecraftMapping.Hookpoint.BLIT_TO_SCREEN);
        assertNotNull(MinecraftMapping.Hookpoint.PRESENT);
        assertNotNull(MinecraftMapping.Hookpoint.ACQUIRE_TEXTURE);
        assertNotNull(MinecraftMapping.Hookpoint.SURFACE_CONFIGURE);
        assertNotNull(MinecraftMapping.Hookpoint.FRAME_GRAPH);

        // Required mappings: the loader cannot operate without them.
        assertTrue(MinecraftMapping.Hookpoint.GPU_DEVICE.isEssential());
        assertTrue(MinecraftMapping.Hookpoint.DEVICE_INFO.isEssential());
        // Optional mappings: absence reduces functionality.
        assertFalse(MinecraftMapping.Hookpoint.FRAME_GRAPH.isEssential(),
                "Pipelines can manage pass ordering without the host frame graph");
        assertFalse(MinecraftMapping.Hookpoint.BLIT_TO_SCREEN.isEssential(),
                "Unavailable presentation interception must not disable the entire loader");
    }

    @Test
    @DisplayName("Candidates Point To Verified Classes")
    void candidatesPointToVerifiedClasses() {
        // Verify mappings target the correct package families. Graphics hooks belong to renderpearl/blaze3d; explicitly listed world, block and camera hooks belong to net.minecraft. Enumerating world hooks prevents accidentally accepting a misplaced graphics mapping.
        java.util.Set<MinecraftMapping.Hookpoint> worldHookpoints = java.util.EnumSet.of(
                MinecraftMapping.Hookpoint.CLIENT_LEVEL,
                MinecraftMapping.Hookpoint.LEVEL_GET_BLOCK_STATE,
                MinecraftMapping.Hookpoint.LEVEL_IS_LOADED,
                MinecraftMapping.Hookpoint.BLOCK_POS,
                MinecraftMapping.Hookpoint.BLOCK_STATE_IS_AIR,
                MinecraftMapping.Hookpoint.BLOCK_STATE_IS_SOLID,
                MinecraftMapping.Hookpoint.CAMERA_RENDER_STATE);

        for (MinecraftMapping.Hookpoint hookpoint : MinecraftMapping.Hookpoint.values()) {
            for (String candidate : hookpoint.candidates()) {
                String className = candidate.contains("#")
                        ? candidate.substring(0, candidate.indexOf('#')) : candidate;
                if (worldHookpoints.contains(hookpoint)) {
                    assertTrue(className.startsWith("net.minecraft."),
                            hookpoint + " is a scene hook and must belong to net.minecraft: "
                                    + className);
                } else {
                    assertTrue(
                            className.startsWith("com.mojang.renderpearl.")
                                    || className.startsWith("com.mojang.blaze3d."),
                            hookpoint + " must belong to renderpearl or blaze3d: " + className);
                }
            }
        }

        // Reject incorrect package names used by early mapping guesses.
        assertTrue(MinecraftMapping.Hookpoint.GPU_DEVICE.candidates().stream()
                        .allMatch(c -> c.startsWith("com.mojang.renderpearl.")),
                "GpuDevice belongs to renderpearl, not blaze3d.systems");
    }

    @Test
    @DisplayName("Probe Is Idempotent")
    void probeIsIdempotent() {
        MinecraftBridge bridge = bridgeWithoutMinecraft(false);
        bridge.probe();
        int first = bridge.mapping().failures().size();

        bridge.probe();

        assertEquals(first, bridge.mapping().failures().size(),
                "probe must reset previous failures before resolving again");
    }

    @Test
    @DisplayName("Backend Description Is Informative")
    void backendDescriptionIsInformative() {
        MinecraftBridge none = bridgeWithoutMinecraft(false);
        none.probe();
        assertTrue(none.describe().contains("未检测到"),
                "Report absent targets accurately: " + none.describe());

        List<CapabilityDescriptor> registered = new ArrayList<>();
        none.registerCapabilities(registered::add);
        CapabilityDescriptor vulkan = find(registered, MinecraftBridge.CAP_VULKAN);
        assertTrue(vulkan.detail().contains("26.3") || vulkan.detail().contains("校准"),
                "Suggest likely causes and next steps: " + vulkan.detail());
    }

    private static CapabilityDescriptor find(List<CapabilityDescriptor> list, String id) {
        return list.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Unregistered capability: " + id));
    }
}
