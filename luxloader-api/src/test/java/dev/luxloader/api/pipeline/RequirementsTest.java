package dev.luxloader.api.pipeline;

import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.capability.CapabilityRegistry;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuVendor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link Requirements#verify} treats capability requirements as mandatory. UNSUPPORTED
 * fails a FALLBACK requirement; optional runtime fallback must not be expressed as a mandatory
 * capability. Also checks that requirement snapshots are immutable.
 */
class RequirementsTest {

    /** Only these capabilities are available; all others are UNSUPPORTED. */
    private static CapabilityRegistry registry(Map<String, CapabilityLevel> levels) {
        return new CapabilityRegistry() {
            @Override
            public CapabilityLevel level(String id) {
                return levels.getOrDefault(id, CapabilityLevel.UNSUPPORTED);
            }

            @Override
            public boolean isRegistered(String id) {
                return levels.containsKey(id);
            }

            @Override
            public Optional<CapabilityDescriptor> find(String id) {
                return Optional.empty();
            }

            @Override
            public List<CapabilityDescriptor> all() {
                return List.of();
            }

            @Override
            public List<CapabilityDescriptor> withPrefix(String prefix) {
                return List.of();
            }

            @Override
            public boolean supportsExtension(String extension) {
                return false;
            }
        };
    }

    /** Minimal device snapshot with no extensions. */
    private static GpuCapabilities bareDevice() {
        return new GpuCapabilities("test-device", GpuVendor.UNKNOWN, 0, 0, 0, "",
                0, Set.of(), Set.of(), 0, 0, 0f, 0L, 0);
    }

    @Test
    @DisplayName("Required Capability Below Level Is AHard Failure")
    void requiredCapabilityBelowLevelIsAHardFailure() {
        Requirements requirements = Requirements.builder()
                .requireCapability("graphics.ray_query", CapabilityLevel.FALLBACK)
                .build();

        // Require FALLBACK when the actual capability is UNSUPPORTED.
        Requirements.Verdict verdict = requirements.verify(
                bareDevice(), registry(Map.of()), false);

        assertFalse(verdict.passed(),
                "A mandatory capability below the required level must reject the pipeline");
        assertTrue(verdict.failures().stream().anyMatch(f -> f.contains("graphics.ray_query")),
                "The failure must identify the capability: " + verdict.failures());
    }

    @Test
    @DisplayName("Required Capability Met Passes")
    void requiredCapabilityMetPasses() {
        Requirements requirements = Requirements.builder()
                .requireCapability("graphics.ray_query", CapabilityLevel.NATIVE)
                .build();

        Requirements.Verdict verdict = requirements.verify(
                bareDevice(),
                registry(Map.of("graphics.ray_query", CapabilityLevel.NATIVE)),
                false);

        assertTrue(verdict.passed(), "A satisfied requirement must pass: " + verdict.failures());
    }

    @Test
    @DisplayName("Without Declaration The Degraded Path Survives")
    void withoutDeclarationTheDegradedPathSurvives() {
        // The pipeline handles optional RT support at runtime instead of declaring a mandatory capability.
        Requirements requirements = Requirements.builder().build();

        Requirements.Verdict verdict = requirements.verify(
                bareDevice(), registry(Map.of()), false);

        assertTrue(verdict.passed(),
                "An undeclared requirement must not prevent fallback: " + verdict.failures());
    }

    @Test
    @DisplayName("Capability Map Is Copied Defensively")
    void capabilityMapIsCopiedDefensively() {
        // Requirements must snapshot the supplied map so later caller mutations cannot alter the published contract.
        java.util.Map<String, CapabilityLevel> mutable = new java.util.HashMap<>();
        mutable.put("graphics.ray_query", CapabilityLevel.NATIVE);

        Requirements requirements = Requirements.builder()
                .requireCapabilities(mutable)
                .build();

        mutable.put("graphics.ray_tracing", CapabilityLevel.FULL);
        mutable.clear();

        assertEquals(1, requirements.requiredCapabilities().size(),
                "Mutating the original map must not affect Requirements: " + requirements.requiredCapabilities());
        assertEquals(CapabilityLevel.NATIVE,
                requirements.requiredCapabilities().get("graphics.ray_query"));
    }

    @Test
    @DisplayName("Preferred Capability Is Only AWarning")
    void preferredCapabilityIsOnlyAWarning() {
        Requirements requirements = Requirements.builder()
                .preferCapability("graphics.ray_query", CapabilityLevel.NATIVE)
                .build();

        Requirements.Verdict verdict = requirements.verify(
                bareDevice(), registry(Map.of()), false);

        assertTrue(verdict.passed(),
                "An unsatisfied preference must not reject loading: " + verdict.failures());
        assertTrue(verdict.warnings().stream().anyMatch(w -> w.contains("graphics.ray_query")),
                "Fallback must produce a warning: " + verdict.warnings());
    }

    @Test
    @DisplayName("Soft And Hard Requirements Coexist")
    void softAndHardRequirementsCoexist() {
        Requirements requirements = Requirements.builder()
                .preferCapability("graphics.ray_query", CapabilityLevel.NATIVE)
                .requireCapability("graphics.timeline_semaphore", CapabilityLevel.NATIVE)
                .build();

        Requirements.Verdict verdict = requirements.verify(
                bareDevice(),
                registry(Map.of("graphics.timeline_semaphore", CapabilityLevel.NATIVE)),
                false);

        assertTrue(verdict.passed(),
                "Satisfied mandatory requirements pass despite unsatisfied preferences: " + verdict.failures());
        assertFalse(verdict.warnings().isEmpty(),
                "The unsatisfied preference must appear in warnings");
    }

    @Test
    @DisplayName("Unregistered Feature Is Reported Instead Of Silently Passing")
    void unregisteredFeatureIsReportedInsteadOfSilentlyPassing() {
        // Feature requirements must be checked even when no feature.* capability was registered.
        Requirements requirements = Requirements.builder()
                .requireFeatures("shaderSampledImageArrayDynamicIndexing")
                .build();

        Requirements.Verdict verdict = requirements.verify(
                bareDevice(), registry(Map.of()), false);

        assertTrue(verdict.passed(),
                "An unverifiable feature must not reject loading: " + verdict.failures());
        assertTrue(verdict.warnings().stream()
                        .anyMatch(w -> w.contains("shaderSampledImageArrayDynamicIndexing")),
                "Unverifiable requirements must produce a warning: " + verdict.warnings());
        assertTrue(verdict.warnings().stream().anyMatch(w -> w.contains("没有生效")),
                "The warning must say unchecked rather than unsatisfied: " + verdict.warnings());
    }

    @Test
    @DisplayName("Registered Feature Is Checked Normally")
    void registeredFeatureIsCheckedNormally() {
        Requirements requirements = Requirements.builder()
                .requireFeatures("shaderSampledImageArrayDynamicIndexing")
                .build();

        Requirements.Verdict verdict = requirements.verify(
                bareDevice(),
                registry(Map.of("feature.shaderSampledImageArrayDynamicIndexing",
                        CapabilityLevel.NATIVE)),
                false);

        assertTrue(verdict.passed(), verdict.failures().toString());
        assertTrue(verdict.warnings().isEmpty(),
                "A verifiable feature must not produce an unchecked warning: " + verdict.warnings());
    }

    @Test
    @DisplayName("Registered But Unusable Feature Still Fails")
    void registeredButUnusableFeatureStillFails() {
        Requirements requirements = Requirements.builder()
                .requireFeatures("shaderSampledImageArrayDynamicIndexing")
                .build();

        Requirements.Verdict verdict = requirements.verify(
                bareDevice(),
                registry(Map.of("feature.shaderSampledImageArrayDynamicIndexing",
                        CapabilityLevel.UNSUPPORTED)),
                false);

        assertFalse(verdict.passed(),
                "A registered unavailable feature must reject loading: " + verdict.failures());
    }
}
