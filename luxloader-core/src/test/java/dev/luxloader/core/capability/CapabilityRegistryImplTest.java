package dev.luxloader.core.capability;

import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests capability negotiation: unregistered capabilities are unavailable, and lower support levels
 * cannot replace higher ones.
 */
class CapabilityRegistryImplTest {

    private CapabilityRegistryImpl registry;

    @BeforeEach
    void setUp() {
        registry = new CapabilityRegistryImpl();
    }

    @Test
    @DisplayName("Unknown Is Unsupported")
    void unknownIsUnsupported() {
        assertEquals(CapabilityLevel.UNSUPPORTED, registry.level("nvidia.dlss.sr"));
        assertFalse(registry.isUsable("nvidia.dlss.sr"));
        assertFalse(registry.isRegistered("nvidia.dlss.sr"));
        assertTrue(registry.find("nvidia.dlss.sr").isEmpty());
    }

    @Test
    @DisplayName("Register And Query")
    void registerAndQuery() {
        registry.register(new CapabilityDescriptor("nvidia.dlss.sr", CapabilityLevel.NATIVE,
                "DLSS 超分", "probe-plugin", "驱动 616.64", java.util.Map.of(), System.nanoTime()));

        assertEquals(CapabilityLevel.NATIVE, registry.level("nvidia.dlss.sr"));
        assertTrue(registry.isUsable("nvidia.dlss.sr"));
        assertTrue(registry.atLeast("nvidia.dlss.sr", CapabilityLevel.FALLBACK));
        assertFalse(registry.atLeast("nvidia.dlss.sr", CapabilityLevel.FULL));
        assertEquals("驱动 616.64", registry.find("nvidia.dlss.sr").orElseThrow().detail());
    }

    @Test
    @DisplayName("Lower Level Cannot Override")
    void lowerLevelCannotOverride() {
        registry.register(new CapabilityDescriptor("graphics.ray_query", CapabilityLevel.NATIVE,
                "光追", "device-probe", "VK_KHR_ray_query 可用", java.util.Map.of(), System.nanoTime()));
        boolean accepted = registry.register(new CapabilityDescriptor("graphics.ray_query",
                CapabilityLevel.UNSUPPORTED, "光追", "author-plugin", "我的管线不打算用",
                java.util.Map.of(), System.nanoTime()));

        assertFalse(accepted, "Lower support levels must not replace higher levels");
        assertEquals(CapabilityLevel.NATIVE, registry.level("graphics.ray_query"));
    }

    @Test
    @DisplayName("Same Or Higher Level Overrides")
    void sameOrHigherLevelOverrides() {
        registry.register(CapabilityDescriptor.of("intel.xess.sr", CapabilityLevel.FALLBACK, "p1"));
        assertTrue(registry.register(CapabilityDescriptor.of("intel.xess.sr", CapabilityLevel.PARTIAL, "p2")));
        assertEquals(CapabilityLevel.PARTIAL, registry.level("intel.xess.sr"));

        assertTrue(registry.register(CapabilityDescriptor.of("intel.xess.sr", CapabilityLevel.PARTIAL, "p3")));
        assertEquals("p3", registry.find("intel.xess.sr").orElseThrow().provider());
    }

    @Test
    @DisplayName("Revoke Pins Unsupported")
    void revokePinsUnsupported() {
        registry.register(CapabilityDescriptor.of("nvidia.ngx", CapabilityLevel.NATIVE, "probe"));
        registry.revoke("nvidia.ngx", "NGX 初始化失败: 缺少 nvngx_dlss.dll");

        assertEquals(CapabilityLevel.UNSUPPORTED, registry.level("nvidia.ngx"));
        assertEquals(1, registry.revocationLog().size());
        assertTrue(registry.revocationLog().get(0).contains("nvngx_dlss.dll"));
    }

    @Test
    @DisplayName("Extension Fallback")
    void extensionFallback() {
        registry.extension("VK_KHR_ray_query", true);
        registry.extension("VK_KHR_ray_tracing_pipeline", false);

        assertTrue(registry.supportsExtension("VK_KHR_ray_query"));
        assertFalse(registry.supportsExtension("VK_KHR_ray_tracing_pipeline"));
        assertFalse(registry.supportsExtension("VK_KHR_never_heard_of_it"));
        assertEquals(CapabilityLevel.NATIVE, registry.level("ext.VK_KHR_ray_query"));
    }

    @Test
    @DisplayName("Select Best Respects Order")
    void selectBestRespectsOrder() {
        registry.register(CapabilityDescriptor.of("dlss", CapabilityLevel.NATIVE, "p"));
        registry.register(CapabilityDescriptor.of("xess", CapabilityLevel.PARTIAL, "p"));
        registry.register(CapabilityDescriptor.of("fsr", CapabilityLevel.FALLBACK, "p"));

        // Preference order: DLSS > XeSS > FSR.
        assertEquals("dlss", registry.selectBest(List.of("dlss", "xess", "fsr"), CapabilityLevel.FALLBACK)
                .orElseThrow());
        // Only DLSS satisfies the NATIVE requirement.
        assertEquals("dlss", registry.selectBest(List.of("xess", "dlss"), CapabilityLevel.NATIVE).orElseThrow());
        // Return empty when no candidate qualifies so the caller can fall back.
        assertTrue(registry.selectBest(List.of("fsr"), CapabilityLevel.NATIVE).isEmpty());
    }

    @Test
    @DisplayName("Select Best With Level")
    void selectBestWithLevel() {
        registry.register(CapabilityDescriptor.of("xess", CapabilityLevel.FALLBACK, "p"));
        var selection = registry.selectBestWithLevel(List.of("dlss", "xess"), CapabilityLevel.FALLBACK)
                .orElseThrow();
        assertEquals("xess", selection.id());
        assertEquals(CapabilityLevel.FALLBACK, selection.level());
    }

    @Test
    @DisplayName("Max Multiplier Attribute")
    void maxMultiplierAttribute() {
        registry.register(new CapabilityDescriptor("dlss.fg", CapabilityLevel.NATIVE, "帧生成",
                "p", "", java.util.Map.of("maxMultiplier", "2"), System.nanoTime()));
        // Register xess.fg without a maxMultiplier property.
        registry.register(new CapabilityDescriptor("xess.fg", CapabilityLevel.PARTIAL, "帧生成",
                "p", "仅超分", java.util.Map.of(), System.nanoTime()));

        assertEquals(2, registry.maxMultiplier("dlss.fg").orElseThrow());
        assertTrue(registry.maxMultiplier("xess.fg").isEmpty(),
                "A registered capability without maxMultiplier must return empty");
        assertTrue(registry.maxMultiplier("nothing").isEmpty(), "An unregistered capability must return empty");
    }

    @Test
    @DisplayName("Queries For Diagnostics")
    void queriesForDiagnostics() {
        registry.register(CapabilityDescriptor.of("graphics.ray_query", CapabilityLevel.NATIVE, "p"));
        registry.register(CapabilityDescriptor.unsupported("nvidia.dlss.fg", "p", "需要 RTX 40 系以上"));

        assertEquals(1, registry.withPrefix("graphics.").size());
        assertEquals(1, registry.withPrefix("nvidia.").size());

        var reasons = registry.unavailableReasons();
        assertEquals(1, reasons.size());
        assertEquals("nvidia.dlss.fg", reasons.get(0).id());
        assertTrue(reasons.get(0).reason().contains("RTX 40"));
    }

    @Test
    @DisplayName("All Is Sorted")
    void allIsSorted()  {
        registry.register(CapabilityDescriptor.of("zzz", CapabilityLevel.NATIVE, "p"));
        registry.register(CapabilityDescriptor.of("aaa", CapabilityLevel.NATIVE, "p"));
        registry.register(CapabilityDescriptor.of("mmm", CapabilityLevel.NATIVE, "p"));
        assertEquals(List.of("aaa", "mmm", "zzz"),
                registry.all().stream().map(CapabilityDescriptor::id).toList());
    }
}
