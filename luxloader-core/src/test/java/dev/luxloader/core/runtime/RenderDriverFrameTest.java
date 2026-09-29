package dev.luxloader.core.runtime;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.frame.FrameContext;
import dev.luxloader.api.gpu.ImageHandle;
import dev.luxloader.api.host.HostAdapter;
import dev.luxloader.api.pipeline.FrameControl;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.FrameSetup;
import dev.luxloader.api.pipeline.GpuResourceProvider;
import dev.luxloader.api.pipeline.PipelineDescriptor;
import dev.luxloader.api.pipeline.PresentRequest;
import dev.luxloader.api.pipeline.RenderContext;
import dev.luxloader.api.pipeline.RenderPass;
import dev.luxloader.api.pipeline.RenderPipeline;
import dev.luxloader.api.pipeline.ResourceRequest;
import dev.luxloader.api.pipeline.StageKind;
import dev.luxloader.api.plugin.HostServices;
import dev.luxloader.api.plugin.RenderDriver;
import dev.luxloader.api.plugin.PipelinePlugin;
import dev.luxloader.api.plugin.PluginBootstrap;
import dev.luxloader.core.vulkan.VulkanApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end frame execution tests covering setup and skip decisions, declaration before recording,
 * dependency-ordered encode calls, failed-pass isolation, present outcomes and next-frame history.
 * Requires a Vulkan device and skips when unavailable.
 */
class RenderDriverFrameTest {

    /** Test pipeline ID. */
    private static final GpuId PIPELINE_ID = new GpuId("dev.luxloader.test", "frame-execution");

    @TempDir
    Path configDir;

    private RenderDriverImpl driver;

    /** Pass encode order shared with test assertions. */
    private static final List<String> encodeOrder = new CopyOnWriteArrayList<>();
    private static boolean failInMiddlePass;
    private static boolean skipFrame;
    private static boolean presentUsesCustomImage;
    private static int setupFrameCount;
    private static int presentCount;

    /** Last frame setup received by the test pipeline. */
    private static FrameSetup lastSetup;

    private static void resetState() {
        encodeOrder.clear();
        failInMiddlePass = false;
        skipFrame = false;
        presentUsesCustomImage = false;
        setupFrameCount = 0;
        presentCount = 0;
        lastSetup = null;
    }

    @BeforeEach
    void setUp() {
        resetState();
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }

    // Test passes and pipelines.

    /**
     * Recording-only pass with no GPU work. Inherit PassHost so failure isolation can disable it through
     * setEnabled(false).
     */
    private static final class RecordingPass extends dev.luxloader.api.pipeline.PassHost {
        private final boolean explode;

        RecordingPass(String name, java.util.Set<String> inputs, java.util.Set<String> outputs,
                      RenderPipeline owner) {
            this(name, inputs, outputs, owner, false);
        }

        RecordingPass(String name, java.util.Set<String> inputs, java.util.Set<String> outputs,
                      RenderPipeline owner, boolean explode) {
            super(PIPELINE_ID.child(name), name, StageKind.CUSTOM_POST, owner);
            reads(inputs.toArray(String[]::new));
            writes(outputs.toArray(String[]::new));
            this.explode = explode;
            if (explode) {
                optional();
            }
        }

        @Override
        protected ResourceRequest doInitialize(RenderContext ctx) {
            return ResourceRequest.EMPTY;
        }

        @Override
        protected void doEncode(FrameGraph graph, FrameContext frame) {
            encodeOrder.add(id().path());
            if (explode) {
                throw new IllegalStateException("测试用：这个阶段故意失败");
            }
        }
    }

    /** Three-pass pipeline declared in reverse dependency order. */
    private static final class FramePipeline implements RenderPipeline {

        private final RecordingPass prepare;
        private final RecordingPass middle;
        private final RecordingPass finalize;

        FramePipeline() {
            this.prepare = new RecordingPass("prepare", java.util.Set.of(),
                    java.util.Set.of("my.tmp"), this);
            this.middle = new RecordingPass("middle", java.util.Set.of("my.tmp"),
                    java.util.Set.of(FrameGraph.FINAL), this);
            this.finalize = new RecordingPass("finalize", java.util.Set.of(FrameGraph.FINAL),
                    java.util.Set.of("my.done"), this);
        }

        @Override
        public PipelineDescriptor descriptor() {
            return PipelineDescriptor.builder(PIPELINE_ID, "帧执行测试管线", "1.0.0")
                    .kind(PipelineDescriptor.PipelineKind.ENHANCEMENT)
                    .frameOwnership(PipelineDescriptor.FrameOwnership.SHARED)
                    .priority(0)
                    .build();
        }

        @Override
        public void initialize(RenderContext ctx) {
        }

        @Override
        public List<RenderPass> passes() {
            return List.of(prepare, middle, finalize);
        }

        @Override
        public FrameControl setupFrame(FrameSetup setup, FrameContext frame) {
            setupFrameCount++;
            lastSetup = setup;
            return skipFrame ? FrameControl.skip("测试用：本帧不介入") : FrameControl.normal();
        }

        @Override
        public void encodeFrame(FrameGraph graph, FrameContext frame) {
            // Declare without recording, and shuffle declarations to verify dependency-based execution.
            graph.addPass(middle);
            graph.addPass(finalize);
            graph.addPass(prepare);
            // This marker must precede every pass invocation.
            encodeOrder.add("declared");
        }

        @Override
        public PresentRequest present(FrameContext frame, PresentRequest.Target target) {
            presentCount++;
            if (!presentUsesCustomImage) {
                return new PresentRequest(target, PresentRequest.Request.DEFAULT);
            }
            return new PresentRequest(target,
                    PresentRequest.Request.of(ImageHandle.vkImage(0xABCDL, "pipeline-output")));
        }

        @Override
        public void close() {
        }
    }

    /** Plugin shell; tests register its pipeline directly with HostServices. */
    public static final class RegistrationShell implements PipelinePlugin {
        @Override
        public dev.luxloader.api.LuxMod mod() {
            return dev.luxloader.api.LuxMod.builder(new GpuId("dev.luxloader.test", "frame-shell"),
                    "帧执行测试外壳", "1.0.0").build();
        }

        @Override
        public void onLoad(PluginBootstrap bootstrap) {
            bootstrap.capability("dev.luxloader.test.frame_shell", CapabilityLevel.NATIVE,
                    "帧执行测试外壳已加载");
        }

        @Override
        public List<GpuId> dependencies() {
            return List.of();
        }
    }

    // Fixtures.

    private void prepareDriverWithActivePipeline() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        driver = new RenderDriverImpl(configDir);
        driver.initialize(RenderDriver.DeviceRequest.defaults());
        Assumptions.assumeTrue(driver.gpuDevice() != null, "Could not create a Vulkan device");

        HostServices host = driver.hostServicesFor(FakeTestPlugin.ID.toString())
                .orElseThrow(() -> new AssertionError("Test plugin HostServices must be available"));

        // Register both failing and normal pipelines under distinct IDs.
        host.registerPipeline(PIPELINE_ID, new FramePipeline().descriptor(), FramePipeline::new);
        assertTrue(driver.activatePipeline(PIPELINE_ID, "测试整帧执行"),
                () -> "Test pipeline activation failed; current state: " + driver.pipelines());
    }

    private static HostAdapter.HostFrameTextures textures() {
        return new HostAdapter.HostFrameTextures(
                ImageHandle.vkImage(0x1000L, "host-color"),
                ImageHandle.vkImage(0x2000L, "host-depth"),
                ImageHandle.vkImage(0x3000L, "host-motion"),
                ImageHandle.vkImage(0x4000L, "host-exposure"),
                ImageHandle.vkImage(0x5000L, "host-ui"),
                ImageHandle.vkImage(0x6000L, "host-swapchain"),
                1920, 1080, 2560, 1440);
    }

    private RenderDriver.PresentOutcome runOneFrame() {
        driver.onFrameBegin();
        RenderDriver.PresentOutcome outcome = driver.onBeforePresent(textures());
        driver.onFrameEnd();
        return outcome;
    }

    // Tests.

    @Test
    @DisplayName("Writes Startup Heartbeat To Disk")
    void writesStartupHeartbeatToDisk() {
        prepareDriverWithActivePipeline();
        runOneFrame();

        // Persist heartbeat progress immediately to diagnose native crashes that bypass JVM error files and shutdown hooks. A previous 0xC0000005 failure during early frames left no other durable trace.
        java.nio.file.Path heartbeat = driver.contentDirectory().resolve("heartbeat.log");
        assertTrue(java.nio.file.Files.exists(heartbeat),
                "A heartbeat file must exist after one frame: " + heartbeat);

        String all;
        try {
            all = String.join("\n", java.nio.file.Files.readAllLines(heartbeat));
        } catch (java.io.IOException e) {
            throw new AssertionError("Could not read heartbeat file: " + heartbeat, e);
        }

        assertFalse(all.isBlank(), "The heartbeat file must not be empty");
        assertTrue(all.contains("frame=1"), "The heartbeat must include a frame index: " + all);
        // Cover the entire frame path so a crash between entry and completion can be located.
        assertTrue(all.contains("开始 #1"), "The heartbeat must include frame entry: " + all);
        assertTrue(all.contains("present 决策完成"),
                "The heartbeat must extend through the present decision: " + all);
    }

    @Test
    @DisplayName("Syncs Frame Setup From Host Textures")
    void syncsFrameSetupFromHostTextures() {
        prepareDriverWithActivePipeline();

        // The host reports render size 1920x1080 and display size 2560x1440. Replace the initial test setup with actual host dimensions; retaining its defaults previously allocated and dispatched incorrectly sized outputs.
        runOneFrame();

        assertNotNull(lastSetup, "The pipeline must receive a frame setup");
        assertEquals(2560, lastSetup.displayWidth(), "Display width must come from the host instead of placeholder 1920");
        assertEquals(1440, lastSetup.displayHeight(), "Display height must come from the host instead of placeholder 1080");
        assertEquals(1920, lastSetup.renderWidth(), "Render width must come from the host");
        assertEquals(1080, lastSetup.renderHeight(), "Render height must come from the host");
        assertEquals(1920f / 2560f, lastSetup.renderScale(), 1e-4f,
                "Render scale must be derived from render/display dimensions");
        assertTrue(lastSetup.resetRequested(), "The first size synchronization must request history reset");

        // The next frame keeps dimensions, advances the frame index and stops requesting reset. A stuck zero index previously reset upscale history every frame.
        runOneFrame();
        assertNotNull(lastSetup, "The second frame must receive a frame setup");
        assertEquals(1L, lastSetup.frameIndex(), "Frame indices must advance rather than remain zero");
        assertFalse(lastSetup.resetRequested(), "Unchanged dimensions must not request another history reset");
        assertEquals(2560, lastSetup.displayWidth(), "Steady-state dimensions must remain unchanged");
    }

    @Test
    @DisplayName("Executes Frame Graph In Dependency Order")
    void executesFrameGraphInDependencyOrder() {
        prepareDriverWithActivePipeline();
        RenderDriver.PresentOutcome outcome = runOneFrame();

        assertEquals(1, setupFrameCount, "setupFrame must run once");
        assertEquals(1, presentCount, "present must run once");
        assertFalse(outcome.useCustomImage(), "The default implementation does not override presentation");

        // The declared marker must come first, proving encodeFrame only declares passes.
        assertEquals(List.of("declared",
                        PIPELINE_ID.child("prepare").path(),
                        PIPELINE_ID.child("middle").path(),
                        PIPELINE_ID.child("finalize").path()),
                encodeOrder,
                "Dependencies must order prepare, middle and finalize, "
                        + "independently of encodeFrame declaration order");
    }

    @Test
    @DisplayName("Exposes Compiled Graph")
    void exposesCompiledGraph() {
        prepareDriverWithActivePipeline();
        runOneFrame();

        FrameGraph.Compiled compiled = driver.lastCompiled();
        assertNotNull(compiled, "A compiled graph must be available after one frame");
        assertTrue(compiled.valid(), compiled.error());
        assertEquals(3, compiled.ordered().size());
        assertEquals(PIPELINE_ID.child("middle").path(),
                compiled.finalProducer().orElseThrow().nodeId().path(),
                "The middle pass writes frame.final and must supply the final output version");
        assertTrue(driver.lastFrameGraph().producedResources().contains("my.done"));
    }

    @Test
    @DisplayName("Skipped Frame Runs Nothing")
    void skippedFrameRunsNothing() {
        prepareDriverWithActivePipeline();
        skipFrame = true;

        runOneFrame();

        assertEquals(1, setupFrameCount);
        assertTrue(encodeOrder.isEmpty(),
                "A skipped frame must execute no passes: " + encodeOrder);
        assertEquals(0, presentCount, "A skipped frame must not run presentation decisions");
    }

    @Test
    @DisplayName("Isolates Failing Pass")
    void isolatesFailingPass() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        driver = new RenderDriverImpl(configDir);
        driver.initialize(RenderDriver.DeviceRequest.defaults());
        Assumptions.assumeTrue(driver.gpuDevice() != null, "Could not create a Vulkan device");

        // Pipeline with a deliberately failing middle pass.
        HostServices host = driver.hostServicesFor(FakeTestPlugin.ID.toString()).orElseThrow();
        GpuId brokenId = new GpuId("dev.luxloader.test", "broken-frame");
        host.registerPipeline(brokenId, PipelineDescriptor.builder(brokenId, "会失败的管线", "1.0.0")
                .kind(PipelineDescriptor.PipelineKind.ENHANCEMENT)
                .priority(0)
                .build(), BrokenPipeline::new);
        assertTrue(driver.activatePipeline(brokenId, "测试失败隔离"));

        runOneFrame();

        assertEquals(List.of(PIPELINE_ID.child("first").path(),
                        PIPELINE_ID.child("boom").path(),
                        PIPELINE_ID.child("last").path()),
                encodeOrder,
                "One failed pass must not prevent later passes from running");
        assertFalse(driver.failedPasses().isEmpty(), "Record the failed pass");
        assertTrue(driver.failedPasses().keySet().stream().anyMatch(k -> k.contains("boom")),
                "The record must identify the failed pass: " + driver.failedPasses());

        // On the next frame, the failed pass must remain disabled to avoid repeated errors.
        encodeOrder.clear();
        runOneFrame();

        assertFalse(encodeOrder.contains(PIPELINE_ID.child("boom").path()),
                "The failed pass must remain disabled next frame: " + encodeOrder);
        assertTrue(encodeOrder.contains(PIPELINE_ID.child("last").path()),
                "Other passes must continue normally: " + encodeOrder);
    }

    /** Pipeline whose middle pass always throws. */
    private static final class BrokenPipeline implements RenderPipeline {
        private final GpuId id = new GpuId("dev.luxloader.test", "broken-frame");
        private final RecordingPass first;
        private final RecordingPass boom;
        private final RecordingPass last;

        BrokenPipeline() {
            this.first = new RecordingPass("first", java.util.Set.of(),
                    java.util.Set.of("my.a"), this);
            this.boom = new RecordingPass("boom", java.util.Set.of("my.a"),
                    java.util.Set.of("my.b"), this, true);
            this.last = new RecordingPass("last", java.util.Set.of("my.b"),
                    java.util.Set.of("my.c"), this);
        }

        @Override
        public PipelineDescriptor descriptor() {
            return PipelineDescriptor.builder(id, "会失败的管线", "1.0.0")
                    .kind(PipelineDescriptor.PipelineKind.ENHANCEMENT)
                    .priority(0)
                    .build();
        }

        @Override
        public void initialize(RenderContext ctx) {
        }

        @Override
        public List<RenderPass> passes() {
            return List.of(first, boom, last);
        }

        @Override
        public void encodeFrame(FrameGraph graph, FrameContext frame) {
            graph.addPass(first);
            graph.addPass(boom);
            graph.addPass(last);
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("Honours Custom Present Image")
    void honoursCustomPresentImage() {
        prepareDriverWithActivePipeline();
        presentUsesCustomImage = true;

        RenderDriver.PresentOutcome outcome = runOneFrame();

        assertTrue(outcome.useCustomImage(), "The host must present the image selected by the pipeline");
        assertNotNull(outcome.image());
        assertEquals(0xABCDL, outcome.image().bits());
        assertEquals(0xABCDL, driver.lastFrameOutput().bits(),
                "Record this frame's output for next-frame history");
    }

    @Test
    @DisplayName("Second Frame Sees History")
    void secondFrameSeesHistory() {
        prepareDriverWithActivePipeline();
        presentUsesCustomImage = true;
        runOneFrame();

        // Run another frame and check that history is available in the graph.
        driver.onFrameBegin();
        driver.onBeforePresent(textures());
        assertTrue(driver.lastFrameGraph().resourceNames().contains(FrameGraph.HISTORY),
                "Register the previous output as frame.history: "
                        + driver.lastFrameGraph().resourceNames());
        assertTrue(driver.lastFrameGraph().texture(FrameGraph.HISTORY).isPresent(),
                "History must resolve to a texture, not just a resource name");
    }

    @Test
    @DisplayName("Provides Host Textures")
    void providesHostTextures() {
        prepareDriverWithActivePipeline();
        runOneFrame();

        var graph = driver.lastFrameGraph();
        assertTrue(graph.texture(FrameGraph.COLOR).isPresent(), "frame.color must be available");
        assertTrue(graph.texture(FrameGraph.DEPTH).isPresent(), "frame.depth must be available");
        assertTrue(graph.texture(FrameGraph.MOTION).isPresent(), "frame.motion must be available");
        assertEquals(0x1000L, graph.texture(FrameGraph.COLOR).orElseThrow().bits());
        assertEquals(0x6000L, graph.texture(FrameGraph.SWAPCHAIN).orElseThrow().bits());
        assertTrue(graph.resourceNames().contains(FrameGraph.FRAME_BEGIN));
        assertTrue(graph.resourceNames().contains(FrameGraph.FRAME_END));
        assertTrue(graph.resourceNames().contains(FrameGraph.FINAL));
    }

    @Test
    @DisplayName("Invalid Graph Runs Nothing")
    void invalidGraphRunsNothing() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        driver = new RenderDriverImpl(configDir);
        driver.initialize(RenderDriver.DeviceRequest.defaults());
        Assumptions.assumeTrue(driver.gpuDevice() != null, "Could not create a Vulkan device");

        HostServices host = driver.hostServicesFor(FakeTestPlugin.ID.toString()).orElseThrow();
        GpuId cyclicId = new GpuId("dev.luxloader.test", "cyclic-frame");
        host.registerPipeline(cyclicId, PipelineDescriptor.builder(cyclicId, "成环的管线", "1.0.0")
                .kind(PipelineDescriptor.PipelineKind.ENHANCEMENT)
                .priority(0)
                .build(), CyclicPipeline::new);
        assertTrue(driver.activatePipeline(cyclicId, "测试无效帧图"));

        runOneFrame();

        assertEquals(List.of("declared"), encodeOrder,
                "An invalid graph must execute no passes: " + encodeOrder);
        FrameGraph.Compiled compiled = driver.lastCompiled();
        assertNotNull(compiled);
        assertFalse(compiled.valid(), "This graph must be invalid");
        assertTrue(compiled.error().contains("循环依赖"), compiled.error());
    }

    /** Two passes with mutually dependent resource accesses form a genuine cycle. */
    private static final class CyclicPipeline implements RenderPipeline {
        private final GpuId id = new GpuId("dev.luxloader.test", "cyclic-frame");
        private final RecordingPass a;
        private final RecordingPass b;

        CyclicPipeline() {
            this.a = new RecordingPass("a", java.util.Set.of("my.b"),
                    java.util.Set.of("my.a"), this);
            this.b = new RecordingPass("b", java.util.Set.of("my.a"),
                    java.util.Set.of("my.b"), this);
        }

        @Override
        public PipelineDescriptor descriptor() {
            return PipelineDescriptor.builder(id, "成环的管线", "1.0.0")
                    .kind(PipelineDescriptor.PipelineKind.ENHANCEMENT)
                    .priority(0)
                    .build();
        }

        @Override
        public void initialize(RenderContext ctx) {
        }

        @Override
        public List<RenderPass> passes() {
            return List.of(a, b);
        }

        @Override
        public void encodeFrame(FrameGraph graph, FrameContext frame) {
            graph.addPass(a);
            graph.addPass(b);
            encodeOrder.add("declared");
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("Tolerates Missing Device")
    void toleratesMissingDevice() {
        driver = new RenderDriverImpl(configDir);
        driver.initialize(RenderDriver.DeviceRequest.attachedToGame());
        assertSame(null, driver.gpuDevice(), "External integration must not create its own device");

        // Frame callbacks must return quietly when no pipeline is active.
        driver.onFrameBegin();
        assertEquals(RenderDriver.PresentOutcome.PASSTHROUGH,
                driver.onBeforePresent(textures()));
        driver.onFrameEnd();
    }

    @Test
    @DisplayName("Tolerates Host Without Textures")
    void toleratesHostWithoutTextures() {
        prepareDriverWithActivePipeline();

        driver.onFrameBegin();
        RenderDriver.PresentOutcome outcome =
                driver.onBeforePresent(HostAdapter.HostFrameTextures.EMPTY);
        driver.onFrameEnd();

        // Expose missing host resources accurately and let the pipeline decide whether to fall back; a compute-only pipeline may generate everything itself.
        assertFalse(outcome.useCustomImage(), "The default implementation does not override presentation");
        assertEquals(1, presentCount, "The pipeline must still receive the present decision");
        assertTrue(driver.lastFrameGraph().texture(FrameGraph.COLOR).isEmpty(),
                "Do not register frame.color when the host supplies no color");
        assertTrue(driver.lastFrameGraph().texture(FrameGraph.SWAPCHAIN).isEmpty());

        // A null frame bypasses pipeline processing entirely.
        resetState();
        assertEquals(RenderDriver.PresentOutcome.PASSTHROUGH, driver.onBeforePresent(null));
        assertEquals(0, presentCount, "Null textures must pass through directly");
    }

    @Test
    @DisplayName("Exposes Counters")
    void exposesCounters() {
        prepareDriverWithActivePipeline();
        runOneFrame();
        runOneFrame();

        assertEquals(0L, driver.submittedCommandBufferCount(),
                "Recording-free test passes must submit no commands");
        assertEquals(0L, driver.historyResetCount(), "No reset has been requested");
    }

    @Test
    @DisplayName("Shell Plugin Is Discovered")
    void shellPluginIsDiscovered() {
        driver = new RenderDriverImpl(configDir);
        driver.initialize(RenderDriver.DeviceRequest.attachedToGame());

        assertTrue(driver.loadedPlugins().contains(FakeTestPlugin.ID.toString()),
                "The test plugin needs HostServices to register pipelines: " + driver.loadedPlugins());
        assertTrue(driver.registeredPipelineCount() >= 3,
                "The test plugin must register at least three pipelines");
    }

    @Test
    @DisplayName("Capability Is Registered Through Real Path")
    void capabilityIsRegisteredThroughRealPath() {
        driver = new RenderDriverImpl(configDir);
        driver.initialize(RenderDriver.DeviceRequest.attachedToGame());

        assertTrue(driver.capabilities().atLeast("dev.luxloader.test.fake_plugin",
                        CapabilityLevel.NATIVE),
                "Plugin capabilities must appear in the registry");
        assertFalse(driver.capabilities()
                        .atLeast(CapabilityDescriptor.Ids.VULKAN_BACKEND, CapabilityLevel.NATIVE),
                "Without a device, the Vulkan backend must be unavailable");
    }
}
