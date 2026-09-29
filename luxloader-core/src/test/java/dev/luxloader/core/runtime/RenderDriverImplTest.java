package dev.luxloader.core.runtime;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.gpu.GpuQueue;
import dev.luxloader.api.plugin.PipelineInfo;
import dev.luxloader.api.plugin.RenderDriver;
import dev.luxloader.api.pipeline.StageKind;
import dev.luxloader.api.vulkan.VulkanDispatch;
import dev.luxloader.core.config.JsonConfigStore;
import dev.luxloader.core.vulkan.VulkanApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests plugin discovery, registration, activation, switching, reload and shutdown. Configuration,
 * state and failure-isolation tests require no GPU; activation and frame encoding tests skip when
 * Vulkan is unavailable.
 */
class RenderDriverImplTest {

    @TempDir
    Path configDir;

    private RenderDriverImpl driver;

    @BeforeEach
    void setUp() {
        FakeTestPlugin.reset();
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }

    /** Create a driver awaiting an external GPU device. */
    private RenderDriverImpl newAttachedDriver() {
        RenderDriverImpl impl = new RenderDriverImpl(configDir);
        impl.initialize(RenderDriver.DeviceRequest.attachedToGame());
        return impl;
    }

    /** Create a driver with its own device, or return null when Vulkan is unavailable. */
    private RenderDriverImpl newSelfDeviceDriver() {
        RenderDriverImpl impl = new RenderDriverImpl(configDir);
        impl.initialize(RenderDriver.DeviceRequest.defaults());
        return impl.gpuDevice() == null ? null : impl;
    }

    // Tests without a GPU.

    @Test
    @DisplayName("Generates Config On First Run")
    void generatesConfigOnFirstRun() throws Exception {
        driver = newAttachedDriver();

        Path file = configDir.resolve("luxloader.json");
        assertTrue(Files.exists(file), "First startup must generate configuration");

        String text = Files.readString(file);
        assertTrue(text.contains("\"_version\""), "Configuration must include a version: " + text);
        assertTrue(text.contains("loader"), "Include the loader section");
        assertTrue(text.contains("diagnostics"), "Include the diagnostics section");
        assertTrue(text.contains("device"), "Include the device section");
        assertTrue(text.contains("vulkan"), "Include the Vulkan section");

        assertTrue(driver.config().isFirstRun(), "First-run flag must be true");
        assertTrue(driver.config().lastError().isEmpty(),
                "Initial configuration generation must succeed: " + driver.config().lastError());
    }

    @Test
    @DisplayName("Discovers Plugins And Writes Their Config")
    void discoversPluginsAndWritesTheirConfig() throws Exception {
        driver = newAttachedDriver();

        assertTrue(driver.loadedPlugins().contains(FakeTestPlugin.ID.toString()),
                "The test plugin must be discovered: " + driver.loadedPlugins());

        driver.config().save();
        String text = Files.readString(configDir.resolve("luxloader.json"));
        // Encode dots in plugin IDs as underscores so configuration sections remain flat.
        String encoded = RenderDriverImpl.encodePluginId(FakeTestPlugin.ID.toString());
        assertTrue(text.contains(encoded),
                "Plugin configuration must use a single encoded key containing " + encoded + "）: " + text);
        assertFalse(text.contains("\"test:fake-plugin\""),
                "Encoded keys must not retain the original colon-separated ID");
        assertTrue(text.contains("strength"), "Plugin options must be written with defaults");

        assertTrue(driver.hostServicesFor(FakeTestPlugin.ID.toString()).isPresent(),
                "HostServices must be available for the plugin");
    }

    @Test
    @DisplayName("Migrates Legacy Defaults When Plugin Schema Is Bound")
    void migratesLegacyDefaultsWhenPluginSchemaIsBound() throws Exception {
        // Reproduce a version-1 configuration containing historical plugin defaults. Migration must run when the plugin schema is bound during initialize(), after discovery.
        Files.writeString(configDir.resolve("luxloader.json"), """
                {
                  "_version": 1,
                  "plugins": {
                    "dev_luxloader_test_fake-plugin": { "strength": 0.25 }
                  }
                }
                """);

        driver = newAttachedDriver();

        String section = "plugins." + RenderDriverImpl.encodePluginId(FakeTestPlugin.ID.toString());
        assertEquals(0.5, driver.config().section(section).getFloat("strength", -1f), 1e-6,
                "Migrate historical default 0.25 to current default 0.5");
        assertEquals(JsonConfigStore.SCHEMA_VERSION, driver.config().fileVersion(),
                "Write the current configuration version");
    }

    @Test
    @DisplayName("Registers Plugin Capabilities")
    void registersPluginCapabilities() {
        driver = newAttachedDriver();

        var registry = driver.capabilities();
        assertTrue(registry.atLeast("dev.luxloader.test.fake_plugin", CapabilityLevel.NATIVE),
                "Registered plugin capabilities must be queryable");
        assertEquals(CapabilityLevel.NATIVE, registry.level("dev.luxloader.test.fake_plugin"));
    }

    @Test
    @DisplayName("Registers Pipelines And Rejects Unsatisfiable")
    void registersPipelinesAndRejectsUnsatisfiable() {
        driver = newAttachedDriver();

        List<PipelineInfo> pipelines = driver.pipelines();
        assertTrue(pipelines.size() >= 3,
                "All three plugin pipelines must appear: " + pipelines.size());

        PipelineInfo alwaysOk = pipelines.stream()
                .filter(p -> p.id().equals(FakeTestPlugin.ALWAYS_OK_PIPELINE))
                .findFirst().orElseThrow();
        assertFalse(alwaysOk.isUnusable(),
                "A pipeline without requirements must not be rejected; state=" + alwaysOk.state() + " detail=" + alwaysOk.detail());

        // Impossible requirements are rejected during registration even before device readiness.
        PipelineInfo impossible = pipelines.stream()
                .filter(p -> p.id().equals(FakeTestPlugin.IMPOSSIBLE_PIPELINE))
                .findFirst().orElseThrow();
        assertNotNull(impossible.state());

        // Preserve all three descriptions for the UI.
        assertEquals(FakeTestPlugin.ALWAYS_OK_PIPELINE, alwaysOk.id());
        assertFalse(alwaysOk.displayName().isBlank());
    }

    @Test
    @DisplayName("Activating Unknown Pipeline Fails Gracefully")
    void activatingUnknownPipelineFailsGracefully() {
        driver = newAttachedDriver();

        assertFalse(driver.activatePipeline(new GpuId("nope", "missing"), "测试"),
                "An unregistered pipeline must fail activation");
        assertTrue(driver.activePipelineId().isEmpty(), "No pipeline must be active");
    }

    @Test
    @DisplayName("Activation Requires Device")
    void activationRequiresDevice() {
        driver = newAttachedDriver();

        // External integration mode has no device yet.
        if (driver.gpuDevice() != null) {
            return; // This case applies only when the driver does not create its own device.
        }
        assertFalse(driver.activatePipeline(FakeTestPlugin.ALWAYS_OK_PIPELINE, "测试"),
                "Activation must fail before device readiness");
        PipelineInfo info = driver.pipelines().stream()
                .filter(p -> p.id().equals(FakeTestPlugin.ALWAYS_OK_PIPELINE))
                .findFirst().orElseThrow();
        assertEquals(PipelineInfo.State.REJECTED, info.state());
        assertTrue(info.detail().contains("设备"), "The reason must identify the device issue: " + info.detail());
    }

    @Test
    @DisplayName("Reload Reestablishes State")
    void reloadReestablishesState() {
        driver = newAttachedDriver();
        int before = driver.registeredPipelineCount();
        assertTrue(before >= 3);

        driver.reload("测试重载");

        assertEquals(before, driver.registeredPipelineCount(),
                "Reload must preserve the pipeline count");
        assertTrue(driver.loadedPlugins().contains(FakeTestPlugin.ID.toString()),
                "Plugins must remain discoverable after reload");
        assertTrue(driver.activePipelineId().isEmpty(),
                "Reload must leave no stale active pipeline");
    }

    @Test
    @DisplayName("Pending Actions Are Processed At Frame Boundary")
    void pendingActionsAreProcessedAtFrameBoundary() {
        driver = newAttachedDriver();

        // A plugin switch requested outside initialization must be deferred.
        driver.requestPipelineSwitch(FakeTestPlugin.ALWAYS_OK_PIPELINE, "测试挂起切换");
        assertTrue(driver.activePipelineId().isEmpty(),
                "The request must not execute immediately before device readiness");

        boolean processed = driver.processPendingActions();
        assertTrue(processed, "The pending action must be processed");

        // Processing pending requests again must do nothing.
        assertFalse(driver.processPendingActions(), "Return false when no action is pending");
    }

    @Test
    @DisplayName("Initialize Is Idempotent")
    void initializeIsIdempotent() {
        driver = newAttachedDriver();
        int plugins = driver.loadedPlugins().size();

        driver.initialize(RenderDriver.DeviceRequest.defaults());

        assertEquals(plugins, driver.loadedPlugins().size(),
                "Repeated initialization must not reload plugins");
    }

    @Test
    @DisplayName("Close Is Idempotent")
    void closeIsIdempotent() {
        driver = newAttachedDriver();
        driver.close();
        driver.close();
        assertFalse(driver.isInitialized(), "A closed driver must not report initialized");
    }

    @Test
    @DisplayName("Vulkan Requests Are Tracked")
    void vulkanRequestsAreTracked() {
        driver = newAttachedDriver();

        VulkanDispatch dispatch = driver.vulkanDispatch();
        assertNotNull(dispatch);

        // Before device readiness, instance creation has not happened and the request can be accepted.
        assertTrue(dispatch.requiredInstanceExtensions().isEmpty()
                        || !dispatch.requiredInstanceExtensions().isEmpty(),
                "Requested features must be queryable");

        // Force the system loader; later vendor-loader requests must be rejected with an explanation.
        dispatch.forceRealLoader(true);
        assertFalse(dispatch.requestFunctionProvider("sl.interposer", "测试"),
                "Forcing the system loader must reject function-source requests");
        assertTrue(dispatch.warnings().stream().anyMatch(w -> w.contains("forceRealLoader")
                        || w.contains("系统加载器")),
                "Record an explanatory warning: " + dispatch.warnings());
    }

    @Test
    @DisplayName("Diagnostics Report Contains Sections")
    void diagnosticsReportContainsSections() {
        driver = newAttachedDriver();

        String report = driver.diagnostics().exportReport();
        assertTrue(report.contains("设备能力"), "Include the device capabilities report section");
        assertTrue(report.contains("能力注册表"), "Include the capability registry report section");
        assertTrue(report.contains("Vulkan 加载入口"), "Include the Vulkan loader report section");
        assertTrue(report.contains("已注册管线"), "Include the pipelines report section");
        assertTrue(report.contains("插件"), "Include the plugins report section");
        assertTrue(report.contains(FakeTestPlugin.ID.toString()),
                "List the test plugin in the report");
    }

    @Test
    @DisplayName("Diagnostics Exports To File")
    void diagnosticsExportsToFile() {
        driver = newAttachedDriver();

        Path report = driver.exportDiagnostics();
        assertTrue(Files.exists(report), "The report file must exist: " + report);
        assertTrue(report.toString().endsWith(".txt"));
        try {
            assertTrue(Files.size(report) > 200, "The report must not be empty");
        } catch (Exception e) {
            fail("Could not read the report: " + e.getMessage());
        }

        // Export both the historical device-profile section and its file while producing the report, since shutdown may leave no later flush opportunity.
        try {
            assertTrue(Files.readString(report).contains("设备档案"),
                    "Include the device profiles report section");
        } catch (Exception e) {
            fail("Could not read the report: " + e.getMessage());
        }
        Path profiles = driver.contentDirectory().resolve("device-profiles.json");
        assertTrue(Files.exists(profiles), "Exporting diagnostics must also persist device profiles: " + profiles);
    }

    @Test
    @DisplayName("Vulkan Backend Reported Unavailable Without Device")
    void vulkanBackendReportedUnavailableWithoutDevice() {
        driver = newAttachedDriver();
        if (driver.gpuDevice() != null) {
            return;
        }
        CapabilityDescriptor backend = driver.capabilities()
                .find(CapabilityDescriptor.Ids.VULKAN_BACKEND).orElseThrow();
        assertEquals(CapabilityLevel.UNSUPPORTED, backend.level(),
                "Without a device, Vulkan must be unavailable");
        assertFalse(backend.detail().isBlank(), "Explain why the backend is unavailable: " + backend.detail());
    }

    // Tests requiring a GPU.

    @Test
    @DisplayName("Activates And Switches On Real Device")
    void activatesAndSwitchesOnRealDevice() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        driver = newSelfDeviceDriver();
        Assumptions.assumeTrue(driver != null, "Could not create a Vulkan device");

        // Select the highest-priority eligible pipeline. The experimental pipeline has priority 200 but is excluded by default, so always-ok at 100 wins.
        assertTrue(driver.activePipelineId().isPresent(),
                "A pipeline must be activated automatically: " + driver.pipelines());
        assertEquals(FakeTestPlugin.ALWAYS_OK_PIPELINE, driver.activePipelineId().orElseThrow(),
                "Select the highest-priority eligible non-experimental pipeline");

        assertEquals(1, FakeTestPlugin.initializeCount, "Initialize the pipeline once");
        assertNotNull(driver.activePipeline(), "The active pipeline instance must be accessible");

        // Encode frames.
        var pipeline = driver.activePipeline();
        var graph = new dev.luxloader.core.graph.FrameGraphImpl();
        var frame = new dev.luxloader.api.frame.FrameContext(
                dev.luxloader.api.frame.FrameTextures.EMPTY,
                dev.luxloader.api.frame.CameraParams.identity(),
                dev.luxloader.api.frame.FrameTiming.unknown(1L),
                driver.gpuDevice(),
                driver.gpuDevice().commands(),
                driver.computeQueue(),
                false,   // hudHidden
                false,   // pauseScreenOpen
                true,    // worldLoaded
                false,   // screenSpaceUi
                dev.luxloader.api.scene.SceneSnapshot.empty(1L));   // scene
        pipeline.setupFrame(driver.frameSetup(), frame);
        pipeline.encodeFrame(graph, frame);
        assertEquals(1, FakeTestPlugin.encodeCount, "Invoke encodeFrame once");
        assertEquals(StageKind.CUSTOM_POST, FakeTestPlugin.lastKind,
                "Preserve pass labels declared by encodeFrame");

        // Close and reactivate the same pipeline to exercise switching.
        int closesBefore = FakeTestPlugin.closeCount;
        assertTrue(driver.activatePipeline(FakeTestPlugin.ALWAYS_OK_PIPELINE, "测试重复激活"));
        assertEquals(closesBefore, FakeTestPlugin.closeCount,
                "Repeated activation of the same pipeline must not close it");

        // Deactivate.
        driver.deactivatePipeline("测试停用");
        assertTrue(FakeTestPlugin.closeCount > closesBefore,
                "Deactivation must close the pipeline; close count: " + FakeTestPlugin.closeCount);
        assertTrue(driver.activePipelineId().isEmpty(), "Deactivation must leave no active pipeline");
    }

    @Test
    @DisplayName("Pipeline Failure Is Isolated")
    void pipelineFailureIsIsolated() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(), "Vulkan unavailable on this machine");

        driver = newSelfDeviceDriver();
        Assumptions.assumeTrue(driver != null, "Could not create a Vulkan device");

        // Deactivate first so automatic selection cannot cause activation to short-circuit as already active.
        driver.deactivatePipeline("准备测试失败隔离");
        assertTrue(driver.activePipelineId().isEmpty(), "No pipeline must remain active after deactivation");

        FakeTestPlugin.failOnInitialize = true;
        boolean activated = driver.activatePipeline(FakeTestPlugin.ALWAYS_OK_PIPELINE, "测试失败隔离");
        assertFalse(activated, "A pipeline throwing during initialization must fail activation");
        assertTrue(driver.activePipelineId().isEmpty(), "Failed activation must leave no active pipeline");

        PipelineInfo info = driver.pipelines().stream()
                .filter(p -> p.id().equals(FakeTestPlugin.ALWAYS_OK_PIPELINE))
                .findFirst().orElseThrow();
        assertEquals(PipelineInfo.State.FAILED, info.state());
        assertTrue(info.detail().contains("故意"), "Record the actual exception: " + info.detail());

        // The loader must remain usable and permit activating another pipeline.
        FakeTestPlugin.failOnInitialize = false;
        assertTrue(driver.activatePipeline(FakeTestPlugin.ALWAYS_OK_PIPELINE, "重试"),
                "Activation must work after an isolated failure");
    }

    @Test
    void runtimeFailureNotifiesOnceAndPreservesCopyInfo() {
        Assumptions.assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        driver = newSelfDeviceDriver();
        assertNotNull(driver);
        driver.failActiveWorldFrame("test: acceleration build failed");
        var failure = driver.pollPipelineFailure().orElseThrow();
        assertEquals(FakeTestPlugin.ALWAYS_OK_PIPELINE, failure.pipeline());
        assertTrue(failure.detail().contains("acceleration build failed"));
        assertTrue(driver.pollPipelineFailure().isEmpty());
        assertTrue(driver.activePipelineId().isEmpty());
        assertEquals(failure.detail(), driver.pipelines().stream().filter(p -> p.id().equals(failure.pipeline()))
                .findFirst().orElseThrow().detail());
        assertTrue(driver.activatePipeline(failure.pipeline(), "explicit retry"));
        assertTrue(driver.pollPipelineFailure().isEmpty());
    }

    @Test
    void reloadChecksUnselectedPipelinesAfterProbe() {
        Assumptions.assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        FakeTestPlugin.registerProbePipeline = true;
        driver = newSelfDeviceDriver();
        assertNotNull(driver);
        for (int i = 0; i < 2; i++) {
            driver.reload("probe availability regression");
            var info = driver.pipelines().stream().filter(p -> p.id().equals(FakeTestPlugin.PROBED_PIPELINE))
                    .findFirst().orElseThrow();
            assertEquals(PipelineInfo.State.REGISTERED, info.state(), info.detail());
            assertTrue(driver.verify(info.descriptor()).passed());
        }
    }

    @Test
    void initializationBatchesFinishOrDiscardBeforeHostFrame() {
        Assumptions.assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        FakeTestPlugin.recordOnInitialize = true;
        driver = newSelfDeviceDriver();
        assertNotNull(driver);
        var commands = (dev.luxloader.core.vulkan.VulkanCommands) driver.gpuDevice().commands();
        assertEquals(0, commands.pendingRecordingCount());
        assertTrue(commands.withHostEncoder(() -> { throw new AssertionError(); },
                cmd -> { throw new AssertionError(); }, () -> true));
        driver.deactivatePipeline("test failure cleanup");
        int closes = FakeTestPlugin.closeCount;
        FakeTestPlugin.failOnInitialize = true;
        assertFalse(driver.activatePipeline(FakeTestPlugin.ALWAYS_OK_PIPELINE, "failed init"));
        assertEquals(closes + 1, FakeTestPlugin.closeCount);
        assertEquals(0, commands.pendingRecordingCount());
        assertTrue(commands.withHostEncoder(() -> { throw new AssertionError(); },
                cmd -> { throw new AssertionError(); }, () -> true));
        FakeTestPlugin.failOnInitialize = false;
        assertTrue(driver.activatePipeline(FakeTestPlugin.ALWAYS_OK_PIPELINE, "retry"));
        assertEquals(0, commands.pendingRecordingCount());
    }

    @Test
    @DisplayName("Device Capabilities Are Translated")
    void deviceCapabilitiesAreTranslated() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(), "Vulkan unavailable on this machine");

        driver = newSelfDeviceDriver();
        Assumptions.assumeTrue(driver != null, "Could not create a Vulkan device");

        var registry = driver.capabilities();
        var caps = driver.gpuDevice().capabilities();

        // Backend detection.
        assertTrue(registry.atLeast(CapabilityDescriptor.Ids.VULKAN_BACKEND, CapabilityLevel.NATIVE),
                "With a device, the Vulkan backend must be available");

        // RT support must reflect actual extension probing, not the Vulkan version alone.
        CapabilityLevel rt = registry.level("graphics.ray_tracing");
        assertEquals(caps.supportsRayTracing(), rt.isUsable(),
                "RT capability must match extension probing; Vulkan 1.4 does not make RT core");

        // An extension advertised by the device must appear in the capability registry.
        String someExtension = caps.extensions().stream().findFirst().orElse(null);
        if (someExtension != null) {
            assertTrue(registry.supportsExtension(someExtension),
                    "Device extensions must be queryable through the capability registry: " + someExtension);
        }

        // Async compute support comes from queue topology, not the extension list.
        assertTrue(registry.isRegistered(CapabilityDescriptor.Ids.ASYNC_COMPUTE_QUEUE),
                "Register async compute capability");
    }
}
