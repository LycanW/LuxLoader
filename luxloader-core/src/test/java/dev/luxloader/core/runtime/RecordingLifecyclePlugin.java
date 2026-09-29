package dev.luxloader.core.runtime;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.pipeline.PipelineDescriptor;
import dev.luxloader.api.plugin.HostServices;
import dev.luxloader.api.plugin.PipelinePlugin;
import dev.luxloader.api.plugin.PluginBootstrap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GPU-free plugin that records loader lifecycle calls per instance. Each driver initialization or
 * reload instantiates a new object, so instance counts and instance identity reveal whether the
 * loader unloads the replaced instances and whether it reuses an old object.
 *
 * <p>Registered through META-INF/services, so it takes part in real discovery. All observations are
 * keyed by this instance's unique plugin ID, which allows several tests to use it in one JVM
 * without a shared counter.
 */
public final class RecordingLifecyclePlugin implements PipelinePlugin {

    private static final AtomicInteger INSTANCE_SEQUENCE = new AtomicInteger();

    /** Every instance created by ServiceLoader discovery, keyed by plugin ID. */
    private static final Map<String, RecordingLifecyclePlugin> INSTANCES = new ConcurrentHashMap<>();

    /** Instances whose onUnload has run, keyed by plugin ID; the object identity is the value. */
    private static final Map<String, RecordingLifecyclePlugin> UNLOADED = new ConcurrentHashMap<>();

    /**
     * Instance-scoped failure control. Every test class in this JVM can load this plugin, so a global
     * switch would break an unrelated driver's plugin while another test had it set. Failures are
     * therefore armed for explicit instance IDs: {@link #armNextLoadFailure()} reserves the instance
     * the caller is about to create, and {@link #armUnloadFailure(String)} targets a known instance.
     */
    private static final AtomicInteger LOAD_RESERVATION = new AtomicInteger();

    private static volatile int reservedLoadFailure = -1;

    private static final java.util.Set<String> FAILING_UNLOADS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** When true, onLoad immediately removes the scene contributor it just registered. */
    public static volatile boolean deregisterContributorOnLoad;

    /**
     * Rendezvous for tests that must observe or extend the plugin's registrations while its onLoad is
     * still running. Staged around one driver initialization; onLoad signals {@code entered} after its
     * own registrations and then waits for {@code release}, so the test can stage competing
     * declarations on live objects before the instance fails or finishes.
     */
    private record LoadRendezvous(java.util.concurrent.CountDownLatch entered,
                                  java.util.concurrent.CountDownLatch release) {
    }

    private static volatile LoadRendezvous rendezvous;

    /** Stages a rendezvous; returns the latch that fires once the plugin's registrations are done. */
    public static java.util.concurrent.CountDownLatch stageLoadRendezvous() {
        LoadRendezvous staged = new LoadRendezvous(
                new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(1));
        rendezvous = staged;
        return staged.entered();
    }

    /** Releases a staged rendezvous, with or without an armed failure. */
    public static void releaseLoadRendezvous() {
        LoadRendezvous staged = rendezvous;
        if (staged != null) {
            staged.release().countDown();
        }
    }

    /** Drops any staged rendezvous. */
    private static void clearLoadRendezvous() {
        rendezvous = null;
    }

    /** Reserves the next constructed instance as a deliberately failing load and returns its tag. */
    public static int armNextLoadFailure() {
        int reservation = LOAD_RESERVATION.incrementAndGet();
        reservedLoadFailure = reservation;
        return reservation;
    }

    /** Makes the instance with this ID throw from onUnload. */
    public static void armUnloadFailure(String instanceId) {
        if (instanceId != null) {
            FAILING_UNLOADS.add(instanceId);
        }
    }

    /** Clears every armed failure and any staged rendezvous. */
    public static void clearArmedFailures() {
        reservedLoadFailure = -1;
        FAILING_UNLOADS.clear();
        deregisterContributorOnLoad = false;
        releaseLoadRendezvous();
        clearLoadRendezvous();
    }

    /** Plugin ID that every instance of this class reports. */
    public static final GpuId ID = new GpuId("dev.luxloader.test", "recording-lifecycle");

    private final String instanceId;
    private final GpuId pluginId;
    private final GpuId contributorId;
    /** Whether this instance was reserved as a deliberately failing load. */
    private final boolean failThisLoad;

    private final AtomicInteger loadCount = new AtomicInteger();
    private final AtomicInteger unloadCount = new AtomicInteger();
    private final AtomicInteger contributionCount = new AtomicInteger();
    private final AtomicInteger unloadFailureCount = new AtomicInteger();

    private HostServices host;
    private PluginBootstrap bootstrap;

    public RecordingLifecyclePlugin() {
        instanceId = ID.namespace() + ":" + ID.path() + "/instance-" + INSTANCE_SEQUENCE.incrementAndGet();
        pluginId = new GpuId(ID.namespace(), instanceId.substring(ID.namespace().length() + 1));
        // A contribution ID must be owned by its registrant, so it follows this instance's own ID.
        contributorId = pluginId.child("terrain");
        int reserved = reservedLoadFailure;
        if (reserved >= 0 && LOAD_RESERVATION.get() == reserved) {
            reservedLoadFailure = -1;
            failThisLoad = true;
        } else {
            failThisLoad = false;
        }
        INSTANCES.put(instanceId, this);
    }

    /** Whether this instance was armed to fail its load. */
    public boolean armedToFailLoad() {
        return failThisLoad;
    }

    /** Whether the instance with this plugin ID has run onUnload. */
    public static boolean wasUnloaded(String pluginId) {
        return UNLOADED.containsKey(pluginId);
    }

    /** Instances created so far, including instances belonging to other tests. */
    public static Map<String, RecordingLifecyclePlugin> instances() {
        return Map.copyOf(INSTANCES);
    }

    /** Whether discovery constructed {@code candidate} exactly once in this JVM. */
    public static boolean constructedOnce(RecordingLifecyclePlugin candidate) {
        return INSTANCES.entrySet().stream()
                .filter(entry -> entry.getValue() == candidate)
                .count() == 1;
    }

    /** This instance's unique plugin ID, used to look it up after the driver replaced it. */
    public String instanceId() {
        return instanceId;
    }

    /** Scene contributor ID this instance registers; a child of its own plugin ID. */
    public GpuId contributorId() {
        return contributorId;
    }

    /** Pipeline ID this instance registers; a child of its own plugin ID. */
    public GpuId pipelineId() {
        return pluginId.child("pipeline");
    }

    /** The host services object handed to this instance during onLoad. */
    public HostServices host() {
        return host;
    }

    public int loadCount() {
        return loadCount.get();
    }

    public int unloadCount() {
        return unloadCount.get();
    }

    public int contributionCount() {
        return contributionCount.get();
    }

    public int unloadFailureCount() {
        return unloadFailureCount.get();
    }

    /** Whether this exact instance ran onUnload; distinguishes instances sharing a plugin ID. */
    public boolean unloaded() {
        return UNLOADED.get(instanceId) == this;
    }

    /** Clears failure switches and observation maps. */
    public static void reset() {
        clearArmedFailures();
        INSTANCES.clear();
        UNLOADED.clear();
    }

    @Override
    public LuxMod mod() {
        return LuxMod.builder(pluginId, "生命周期记录插件（测试用）", "1.0.0")
                .description("记录 onLoad/onUnload 与场景贡献注册，用于验证生命周期合同")
                .license("MIT")
                .build();
    }

    @Override
    public void onLoad(PluginBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.host = bootstrap.host();
        loadCount.incrementAndGet();
        host.registerSceneContributor(contributorId, scene -> {
            contributionCount.incrementAndGet();
            return java.util.List.of();
        });
        // Registrations a rollback must be able to undo, recorded before the deliberate failure.
        host.registerCapability("dev.luxloader.test.recording_lifecycle", true,
                "生命周期记录插件已加载");
        host.registerPipeline(pipelineId(), PipelineDescriptor
                .builder(pipelineId(), "Recording lifecycle pipeline", "1.0.0")
                .description("Never activated; exists to verify rollback of a failed load")
                .build(), RecordingPipeline::new);
        if (deregisterContributorOnLoad) {
            host.deregisterSceneContributor(contributorId);
        }
        // Keep the staged rendezvous readable: the release side reads the same object, and countDown
        // on an already released latch is harmless. Clearing it here created a window where the test
        // could count down the wrong latch after the plugin had already started waiting.
        LoadRendezvous staged = rendezvous;
        if (staged != null) {
            staged.entered().countDown();
            try {
                staged.release().await(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (failThisLoad) {
            throw new IllegalStateException("测试用：故意让 onLoad 失败");
        }
    }

    @Override
    public void onUnload() {
        if (FAILING_UNLOADS.remove(instanceId)) {
            unloadFailureCount.incrementAndGet();
            throw new IllegalStateException("测试用：故意让 onUnload 失败");
        }
        unloadCount.incrementAndGet();
        UNLOADED.put(instanceId, this);
    }

    /** Whether this instance received a bootstrap during onLoad. */
    public boolean loaded() {
        return bootstrap != null;
    }

    /** Minimal pipeline that creates no GPU resources and is never expected to activate. */
    private static final class RecordingPipeline implements dev.luxloader.api.pipeline.RenderPipeline {

        @Override
        public dev.luxloader.api.pipeline.PipelineDescriptor descriptor() {
            return PipelineDescriptor.builder(ID.child("pipeline"), "Recording lifecycle pipeline", "1.0.0").build();
        }

        @Override
        public void initialize(dev.luxloader.api.pipeline.RenderContext context) {
            // Never expected to run: this pipeline exists to verify that a failed load removes it.
            throw new IllegalStateException("test: rollout pipeline must not initialize");
        }

        @Override
        public java.util.List<dev.luxloader.api.pipeline.RenderPass> passes() {
            return java.util.List.of();
        }

        @Override
        public void encodeFrame(dev.luxloader.api.pipeline.FrameGraph graph,
                                dev.luxloader.api.frame.FrameContext frame) {
        }

        @Override
        public void close() {
        }
    }
}
