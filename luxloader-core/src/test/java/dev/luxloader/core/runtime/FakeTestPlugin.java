package dev.luxloader.core.runtime;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.config.ConfigSchema;
import dev.luxloader.api.frame.FrameContext;
import dev.luxloader.api.pipeline.FrameControl;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.FrameSetup;
import dev.luxloader.api.pipeline.PipelineDescriptor;
import dev.luxloader.api.pipeline.RenderContext;
import dev.luxloader.api.pipeline.RenderPass;
import dev.luxloader.api.pipeline.RenderPipeline;
import dev.luxloader.api.pipeline.Requirements;
import dev.luxloader.api.pipeline.StageKind;
import dev.luxloader.api.plugin.PipelinePlugin;
import dev.luxloader.api.plugin.PluginBootstrap;
import dev.luxloader.api.config.ConfigOption;

import java.util.List;

/**
 * GPU-free test plugin for discovery, registration, activation and switching. Register through
 * META-INF/services so RenderDriverImpl.initialize exercises real service discovery. Pipeline
 * construction is deferred until activation.
 */
public final class FakeTestPlugin implements PipelinePlugin {

    /** Plugin ID. */
    public static final GpuId ID = new GpuId("dev.luxloader.test", "fake-plugin");

    /** Unconditionally available pipeline for activation and switching tests. */
    public static final GpuId ALWAYS_OK_PIPELINE = ID.child("always-ok");

    /** Pipeline with impossible requirements, expected to be REJECTED. */
    public static final GpuId IMPOSSIBLE_PIPELINE = ID.child("impossible");

    /** Experimental pipeline excluded from default automatic selection. */
    public static final GpuId EXPERIMENTAL_PIPELINE = ID.child("experimental");

    /** Lifecycle invocation counters for assertions. */
    public static int initializeCount;
    public static int closeCount;
    public static int encodeCount;
    /** Most recently received pass label for graph attachment checks; it does not affect scheduling. */
    public static StageKind lastKind;
    /** When true, initialize throws to test failure isolation. */
    public static boolean failOnInitialize;
    public static boolean recordOnInitialize;
    public static boolean registerProbePipeline;
    public static final GpuId PROBED_PIPELINE = ID.child("probed");
    /** Record frame setups to verify resolution changes. */
    public static FrameSetup lastSetup;

    /** Reset static state before each test method. */
    public static void reset() {
        initializeCount = 0;
        closeCount = 0;
        encodeCount = 0;
        lastKind = null;
        lastSetup = null;
        failOnInitialize = false;
        recordOnInitialize = false;
        registerProbePipeline = false;
    }

    @Override
    public LuxMod mod() {
        return LuxMod.builder(ID, "假插件（测试用）", "1.0.0")
                .description("不接触 GPU，用于验证加载器的加载/注册/激活/切换链路")
                .license("MIT")
                .build();
    }

    @Override
    public void onLoad(PluginBootstrap bootstrap) {
        if (registerProbePipeline) {
            var descriptor = PipelineDescriptor.builder(PROBED_PIPELINE, "Probe dependent", "1")
                    .requirements(Requirements.builder().requireCapability(
                            "dev.luxloader.test.probe_ran", CapabilityLevel.NATIVE).build()).build();
            bootstrap.host().registerPipeline(PROBED_PIPELINE, descriptor, AlwaysOkPipeline::new);
        }
        // Cover available, rejected and experimental pipelines.
        bootstrap.host().registerPipeline(ALWAYS_OK_PIPELINE, alwaysOkDescriptor(), AlwaysOkPipeline::new);
        bootstrap.host().registerPipeline(IMPOSSIBLE_PIPELINE, impossibleDescriptor(),
                AlwaysOkPipeline::new);
        bootstrap.host().registerPipeline(EXPERIMENTAL_PIPELINE, experimentalDescriptor(),
                AlwaysOkPipeline::new);

        // Register a custom capability to exercise the registry.
        bootstrap.capability("dev.luxloader.test.fake_plugin", CapabilityLevel.NATIVE,
                "假插件已加载，用于链路验证");
    }

    @Override
    public void probe(PluginBootstrap bootstrap) {
        // Register a device-dependent fact after device initialization to verify probe timing.
        bootstrap.capability("dev.luxloader.test.probe_ran", true,
                bootstrap.deviceReady() ? "probe 阶段设备已就绪" : "probe 阶段设备未就绪");
    }

    @Override
    public ConfigSchema declareConfig() {
        return ConfigSchema.builder()
                .add(ConfigOption.bool("enabled", true).name("启用").group("常规").build())
                .add(ConfigOption.number("strength", 0.5)
                        // Declare a historical default so migration tests exercise plugin schema binding during initialization.
                        .legacyDefault(0.25)
                        .name("强度").range(0.0, 1.0).group("画质").build())
                .build();
    }

    @Override
    public List<GpuId> dependencies() {
        return List.of();
    }

    // Pipeline descriptions.

    private static PipelineDescriptor alwaysOkDescriptor() {
        return PipelineDescriptor.builder(ALWAYS_OK_PIPELINE, "始终可用（测试）", "1.0.0")
                .kind(PipelineDescriptor.PipelineKind.ENHANCEMENT)
                .priority(100)
                .description("不声明任何前提，任何设备上都可激活")
                .build();
    }

    private static PipelineDescriptor impossibleDescriptor() {
        return PipelineDescriptor.builder(IMPOSSIBLE_PIPELINE, "前提不可能满足（测试）", "1.0.0")
                .kind(PipelineDescriptor.PipelineKind.ENHANCEMENT)
                // Use the highest priority so automatic selection first encounters this rejected pipeline, leaving ALWAYS_OK inactive for explicit activation tests.
                .priority(150)
                .description("要求一个必然缺失的扩展，用于验证注册后被标记为 REJECTED")
                .requirements(Requirements.builder()
                        .requireExtensions("VK_LUXLOADER_THIS_EXTENSION_DOES_NOT_EXIST")
                        .build())
                .build();
    }

    private static PipelineDescriptor experimentalDescriptor() {
        return PipelineDescriptor.builder(EXPERIMENTAL_PIPELINE, "实验性（测试）", "1.0.0")
                .kind(PipelineDescriptor.PipelineKind.ENHANCEMENT)
                .priority(200)
                .experimental(true)
                .description("优先级最高但标记为实验性；默认不参与自动选择")
                .build();
    }

    // Pipeline implementations.

    /** Minimal pipeline that creates no GPU resources. */
    public static final class AlwaysOkPipeline implements RenderPipeline {

        private PipelineDescriptor descriptor;
        private RenderContext context;

        @Override
        public PipelineDescriptor descriptor() {
            return descriptor == null ? alwaysOkDescriptor() : descriptor;
        }

        @Override
        public void initialize(RenderContext ctx) {
            this.context = ctx;
            this.descriptor = ctx.services() == null ? alwaysOkDescriptor() : alwaysOkDescriptor();
            initializeCount++;
            if (recordOnInitialize) {
                ctx.device().commands().begin("initialization-complete").end();
                var unfinished = ctx.device().commands().begin("initialization-second");
                if (!failOnInitialize) unfinished.end();
            }
            if (failOnInitialize) {
                throw new IllegalStateException("测试用：故意让初始化失败");
            }
            ctx.services().diagnostics().info("假管线已初始化（测试用）");
        }

        @Override
        public List<RenderPass> passes() {
            return List.of();
        }

        @Override
        public FrameControl setupFrame(FrameSetup setup, FrameContext frame) {
            lastSetup = setup;
            return FrameControl.normal();
        }

        @Override
        public void encodeFrame(FrameGraph graph, FrameContext frame) {
            encodeCount++;
            lastKind = StageKind.CUSTOM_POST;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
