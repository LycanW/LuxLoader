package dev.luxloader.api.plugin;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityRegistry;
import dev.luxloader.api.config.ConfigSchema;
import dev.luxloader.api.diag.Diagnostics;
import dev.luxloader.api.nativebridge.NativeBridge;
import dev.luxloader.api.pipeline.PipelineDescriptor;
import dev.luxloader.api.pipeline.PipelineSettings;
import dev.luxloader.api.pipeline.RenderPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Default host-side checks for scene contribution registration. A host that does not support scene
 * contributors keeps the unsupported behavior, while namespace validation happens before any host
 * call so an invalid ID is rejected consistently everywhere.
 */
class HostServicesSceneContributorTest {

    private static final GpuId PLUGIN_ID = new GpuId("dev.luxloader.test", "plugin");

    /** Minimal host stub: only the methods this contract test needs do anything. */
    private static final class StubHost implements HostServices {

        @Override
        public LuxMod mod() {
            return LuxMod.builder(PLUGIN_ID, "Test plugin", "1.0.0").build();
        }

        @Override
        public void registerPipeline(GpuId id, PipelineDescriptor descriptor,
                                     Supplier<RenderPipeline> factory) {
        }

        @Override
        public void registerCapability(CapabilityDescriptor descriptor) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void registerNativeLibraryPath(String relativePath) {
        }

        @Override
        public NativeBridge nativeBridge() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConfigSchema declareConfig() {
            return ConfigSchema.builder().build();
        }

        @Override
        public PipelineSettings settings() {
            return null;
        }

        @Override
        public Diagnostics diagnostics() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CapabilityRegistry capabilities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GpuId> registeredPipelineIds() {
            return List.of();
        }

        @Override
        public String loaderVersion() {
            return "test";
        }

        @Override
        public String gameVersion() {
            return "";
        }

        @Override
        public void requestReload(String reason) {
        }

        @Override
        public void requestPipelineSwitch(GpuId pipelineId, String reason) {
        }

        @Override
        public Optional<GpuId> activePipeline() {
            return Optional.empty();
        }
    }

    private static HostServices host() {
        return new StubHost();
    }

    @Test
    @DisplayName("Rejects A Contribution Outside The Plugin Namespace")
    void rejectsContributionOutsidePluginNamespace() {
        HostServices host = host();

        IllegalArgumentException wrongNamespace = assertThrows(IllegalArgumentException.class,
                () -> host.registerSceneContributor(new GpuId("dev.other", "plugin/terrain"), scene -> null));
        assertTrue(wrongNamespace.getMessage().contains(PLUGIN_ID.toString()),
                "Name the registering plugin: " + wrongNamespace.getMessage());

        IllegalArgumentException unrelatedPath = assertThrows(IllegalArgumentException.class,
                () -> host.registerSceneContributor(new GpuId("dev.luxloader.test", "other/terrain"), scene -> null));
        assertTrue(unrelatedPath.getMessage().contains("own namespace"),
                "Explain the ownership rule: " + unrelatedPath.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> host.registerSceneContributor(null, scene -> null));
        assertThrows(IllegalArgumentException.class,
                () -> host.registerSceneContributor(PLUGIN_ID.child("terrain"), null));
    }

    @Test
    @DisplayName("Still Reports Unsupported Hosts")
    void stillReportsUnsupportedHosts() {
        HostServices host = host();
        GpuId contribution = PLUGIN_ID.child("terrain");

        UnsupportedOperationException register = assertThrows(UnsupportedOperationException.class,
                () -> host.registerSceneContributor(contribution, scene -> null));
        assertEquals("Host does not expose scene contributors", register.getMessage());

        assertThrows(UnsupportedOperationException.class,
                () -> host.deregisterSceneContributor(contribution));
    }
}
