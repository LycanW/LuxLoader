package dev.luxloader.mc.neoforge;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.host.HostAdapter;
import dev.luxloader.api.plugin.RenderDriver;
import dev.luxloader.core.runtime.RenderDriverImpl;
import dev.luxloader.mc.MinecraftBridge;
import dev.luxloader.mc.MinecraftBehaviorEventAccess;
import dev.luxloader.mc.MinecraftClientStateAccess;
import dev.luxloader.mc.MinecraftGraphicsAccess;
import dev.luxloader.mc.MinecraftHostAdapter;
import dev.luxloader.mc.hooks.ClientBehaviorSignal;
import dev.luxloader.mc.hooks.RenderHookHost;
import dev.luxloader.mc.hooks.RenderHooks;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * NeoForge client entry. Minecraft prepares the scene; the shared LevelRenderer
 * hook composes the selected plugin plan into its world frame graph. NeoForge
 * frame events attach the host device and process pending selection changes.
 */
@Mod(value = "luxloader", dist = Dist.CLIENT)
public final class LuxLoaderNeoForgeClient implements RenderHookHost {

    @Override
    public java.util.List<dev.luxloader.api.vulkan.VulkanFeatureSetRequest> requestedVulkanFeatureSets() {
        RenderDriverImpl current = driver;
        return current == null ? java.util.List.of()
                : current.vulkanDispatch().requestedDeviceFeatureSets();
    }

    /** Loader metadata identifier. */
    public static final GpuId ID = new GpuId("dev.luxloader", "loader");

    private static final Logger LOGGER = LoggerFactory.getLogger("LuxLoader");

    private static RenderDriverImpl driver;
    private static MinecraftBridge bridge;
    private static MinecraftHostAdapter hostAdapter;
    private static MinecraftClientStateAccess clientStateAccess;
    private static MinecraftBehaviorEventAccess behaviorEventAccess;
    private static boolean clientStateAccessWarningLogged;
    private static boolean behaviorEventAccessWarningLogged;
    private static boolean vulkanBackend;
    private static String gameVersion = "";

    /** Attempt adapter installation once; repeated failures must not stall every frame. */
    private static boolean adapterAttempted;
    /** Adapter installation failure included in diagnostics. */
    private static String adapterFailure = "";
    /** Whether verbose logging is enabled. */
    private static boolean verbose;

    /** Processed frame count for diagnostics. */
    private static long frameCount;

    /**
     * Records whether the shared mixin hook actually ran. host.frame_hook is based exclusively on observed
     * onBeforePresent calls, never configuration or inference.
     */
    private static boolean hooksInstalled;
    private static boolean soundBackendAttached;

    private static boolean worldGraphFrame;
    private static boolean worldGraphSubmitted;
    private static int missingWorldFrames;

    @Override
    public void onClientTick(Object minecraft) {
        RenderDriverImpl current = driver;
        if (current == null || !current.isInitialized()) return;
        try {
            MinecraftClientStateAccess access = clientStateAccess(minecraft);
            boolean paused = access.isPaused(minecraft);
            current.observeClientStateTick(paused, (sequence, time) -> sample(access, minecraft, sequence, time));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnClientStateAccess(e);
        }
    }

    @Override
    public void onClientSafePoint(Object minecraft) {
        RenderDriverImpl current = driver;
        if (current == null || !current.isInitialized()) return;
        try {
            MinecraftClientStateAccess access = clientStateAccess(minecraft);
            long sessionGeneration = access.observeSessionGeneration(minecraft);
            if (!soundBackendAttached) {
                current.attachSoundBackend(new dev.luxloader.mc.hooks.MinecraftSoundBackend((net.minecraft.client.Minecraft)minecraft));
                soundBackendAttached = true;
            }
            current.observeClientStateSafePoint(access.isPaused(minecraft), sessionGeneration,
                    (sequence, time) -> sample(access, minecraft, sequence, time));
            boolean eventDemand = current.hasClientEventSubscribers();
            if (!behaviorEventAccessWarningLogged && (eventDemand || behaviorEventAccess != null)) {
                try {
                    behaviorEventAccess(access, current, minecraft).onSafePoint(minecraft, eventDemand);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    warnBehaviorEventAccess(e);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnClientStateAccess(e);
        }
    }

    @Override
    public boolean wantsClientBehaviorCapture() {
        RenderDriverImpl current = driver;
        return current != null && current.hasClientEventSubscribers() && !behaviorEventAccessWarningLogged;
    }

    @Override
    public void onClientBehaviorSignal(Object minecraft, ClientBehaviorSignal signal) {
        RenderDriverImpl current = driver;
        if (current == null || !current.isInitialized() || !current.hasClientEventSubscribers()
                || behaviorEventAccessWarningLogged) return;
        Object client = minecraft == null ? net.minecraft.client.Minecraft.getInstance() : minecraft;
        if (client == null) return;
        try {
            MinecraftClientStateAccess stateAccess = clientStateAccess(client);
            behaviorEventAccess(stateAccess, current, client).capture(client, signal);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnBehaviorEventAccess(e);
        }
    }

    private static synchronized MinecraftClientStateAccess clientStateAccess(Object minecraft)
            throws ReflectiveOperationException {
        if (clientStateAccess == null) {
            clientStateAccess = new MinecraftClientStateAccess(minecraft.getClass().getClassLoader());
        }
        return clientStateAccess;
    }

    private static synchronized MinecraftBehaviorEventAccess behaviorEventAccess(
            MinecraftClientStateAccess stateAccess, RenderDriverImpl current, Object minecraft)
            throws ReflectiveOperationException {
        if (behaviorEventAccess == null) {
            behaviorEventAccess = new MinecraftBehaviorEventAccess(
                    minecraft.getClass().getClassLoader(), stateAccess, current);
        }
        return behaviorEventAccess;
    }

    private static dev.luxloader.api.state.ClientStateSnapshot sample(MinecraftClientStateAccess access,
            Object minecraft, long sequence, dev.luxloader.api.state.ClientStateSnapshot.LogicalTime time) {
        try {
            return access.sample(minecraft, sequence, time);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException("Minecraft client state capture failed", e);
        }
    }

    private static synchronized void warnClientStateAccess(Throwable error) {
        if (clientStateAccessWarningLogged) return;
        clientStateAccessWarningLogged = true;
        LOGGER.warn(tr("Client state integration is unavailable; observations will be skipped"), error);
    }

    private static synchronized void warnBehaviorEventAccess(Throwable error) {
        if (behaviorEventAccessWarningLogged) return;
        behaviorEventAccessWarningLogged = true;
        LOGGER.warn(tr("Client behavior event integration is unavailable; event capture will be skipped"), error);
    }

    public LuxLoaderNeoForgeClient(IEventBus modEventBus) {
        // Register the hook host before initialization. Later mixin callbacks remain safe even when initialization fails: uninstalled hooks pass through and installed hooks guard the null driver.
        RenderHooks.install(this);

        Path configDir = FMLPaths.CONFIGDIR.get().resolve("luxloader");

        // Read the game version from SharedConstants for adapter descriptions and diagnostics; it is available before window creation.
        try {
            gameVersion = net.minecraft.SharedConstants.getCurrentVersion().name();
        } catch (RuntimeException | LinkageError e) {
            gameVersion = "";
        }

        LuxMod mod = LuxMod.builder(ID, "LuxLoader", RenderDriverImpl.VERSION)
                .authors("LuxLoader")
                .description(tr("Rendering pipeline loader for Minecraft's Vulkan backend"))
                .license("MIT")
                .build();

        LOGGER.info(tr("Initializing LuxLoader {} (NeoForge client)"), RenderDriverImpl.VERSION);

        boolean verbose = readVerbose(configDir);
        LuxLoaderNeoForgeClient.verbose = verbose;

        try {
            bridge = new MinecraftBridge(mod, verbose);
            bridge.probe();
            vulkanBackend = bridge.isVulkanBackend();
            if (verbose) {
                LOGGER.info(tr("Minecraft integration probe:\n{}"), bridge.describe());
            }

            driver = new RenderDriverImpl(configDir, FMLPaths.GAMEDIR.get().resolve("luxloader"));
            driver.initialize(RenderDriver.DeviceRequest.attachedToGame());
            bridge.registerCapabilities(driver::registerCapability);

            if (!vulkanBackend) {
                LOGGER.info(tr("The active backend is not Vulkan; LuxLoader will remain idle. ")
                        + tr("(Mojang marks Vulkan experimental since 26.2 Snapshot 8; OpenGL is the default)"));
            } else if (!driver.deviceReady()) {
                LOGGER.warn(tr("Vulkan detected; waiting for the game device handle. ")
                        + tr("The pipeline activates when the host adapter supplies a device."));
            }

            // The event boundary handles only work that does not require frame textures.
            NeoForge.EVENT_BUS.addListener(this::onRenderFramePre);
            NeoForge.EVENT_BUS.addListener(this::onRenderFramePost);

            // Add the pipeline selector to the video settings footer.
            NeoForge.EVENT_BUS.addListener(this::onScreenInit);

            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownQuietly, "luxloader-shutdown"));

            LOGGER.info(tr("LuxLoader initialized: {} plugins, {} pipelines, active {}"),
                    driver.loadedPlugins().size(),
                    driver.registeredPipelineCount(),
                    driver.activePipelineId().map(Object::toString).orElse(tr("LuxLoaderNeoForgeClient.c7bcc6d27f", "(none)")));

        } catch (RuntimeException | LinkageError e) {
            LOGGER.error(tr("LuxLoader initialization failed; rendering will remain unchanged"), e);
            shutdownQuietly();
        }
    }

    // RenderFrameEvent integration.

    /**
     * Fallback frame start: attach the device and drain pending reload/switch actions. adapterAttempted
     * prevents duplicate installation after the hook path. Draining an empty queue is harmless, and
     * retaining this path lets UI switching work if mixin injection fails.
     */
    private void onRenderFramePre(RenderFrameEvent.Pre event) {
        RenderDriverImpl current = driver;
        if (current == null || !current.isInitialized()) {
            return;
        }
        try {
            ensureHostAdapter(current, currentWindowSurface());
            current.processPendingActions();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Failed to process pending actions"), e);
        }
    }

    /** Frame end: unattended automatic exit. */
    private void onRenderFramePost(RenderFrameEvent.Post event) {
        // Run first and without preconditions; see AUTO_QUIT_NANOS.
        maybeAutoQuit();
        // Hooks own onFrameFinished. Calling it here as well would double count frames and invalidate evidence that the pipeline ran.
    }

    @Override
    public dev.luxloader.api.pipeline.WorldFramePlan worldFramePlan() {
        RenderDriverImpl current = driver;
        if (current == null || !current.isInitialized() || hostAdapter == null
                || !current.deviceReady()) {
            return null;
        }
        current.onFrameBegin();
        var plan = current.activeWorldFramePlan();
        worldGraphFrame = plan != null;
        return plan;
    }

    @Override public boolean usesPreparedEntityShadows() {
        RenderDriverImpl current = driver;
        return current == null || current.activePipeline() == null
                || current.activePipeline().usesPreparedEntityShadows();
    }

    @Override public boolean requiresDynamicGeometry() {
        RenderDriverImpl current = driver;
        return current != null && current.activePipeline() != null
                && current.activePipeline().requiresDynamicGeometry();
    }

    @Override
    public void executeWorldPipeline(Object renderTarget) {
        if (!(renderTarget instanceof com.mojang.blaze3d.pipeline.RenderTarget target)) {
            return;
        }
        RenderDriverImpl current = driver;
        MinecraftHostAdapter adapter = hostAdapter;
        if (current == null || adapter == null || current.gpuDevice() == null
                || target.getColorTexture() == null || target.getColorTextureView() == null) {
            return;
        }
        try {
            var frontend = com.mojang.blaze3d.systems.RenderSystem.getDevice()
                    .createCommandEncoder();
            if (!(frontend instanceof com.mojang.renderpearl.frontend.FrontendCommandEncoder host)
                    || !(host.backend() instanceof
                    com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder vulkan)) {
                throw new IllegalStateException("Minecraft did not expose its Vulkan frame encoder");
            }
            int colorFormat = com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(
                    target.getColorTexture().getFormat());
            var depth = target.getDepthTexture();
            adapter.captureFrame(target.getColorTextureView(), colorFormat,
                    target.getColorTexture().usage(), target.getDepthTextureView(),
                    depth == null ? 0 : com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(
                            depth.getFormat()), depth == null ? 0 : depth.usage());
            long image = adapter.graphics().vkImageOf(target.getColorTextureView()).orElse(0L);
            if (image == 0L) {
                throw new IllegalStateException("Cannot resolve world target VkImage");
            }
            adapter.setFrameTargetOverride(image, colorFormat, target.width, target.height);
            worldGraphSubmitted = true;
            current.onPreparedWorldFrame(adapter.frameTextures(),
                    vulkan::allocateAndBeginTransientCommandBuffer, vulkan::execute);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Plugin world frame failed; retaining recorded host rendering for this frame"), e);
        } finally {
            adapter.setFrameTargetOverride(0L, 0, 0, 0);
        }
    }

    @Override
    public void onPresentBoundary(Object windowSurface) {
        RenderDriverImpl current = driver;
        if (current == null || !current.isInitialized()) {
            return;
        }
        try {
            ensureHostAdapter(current, windowSurface);
            markHooksInstalled(current);
            if (!worldGraphFrame) {
                current.onFrameBegin();
            }
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (worldGraphSubmitted || minecraft == null || minecraft.level == null
                    || current.activePipelineId().isEmpty()) {
                missingWorldFrames = 0;
            } else if (++missingWorldFrames >= 5) {
                current.failActiveWorldFrame(tr("No plugin world stage executed for five consecutive frames; check the LevelRenderer mixin and WorldFramePlan"));
                missingWorldFrames = 0;
            }
            worldGraphFrame = false;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("LuxLoader frame boundary failed; retaining game output"), e);
        }
    }

    /**
     * Called at VulkanDevice.close HEAD before the host destroys its device. Never propagate exceptions
     * into host shutdown or run plugin callbacks here; callbacks belong to loader shutdown.
     */
    @Override
    public void onHostDeviceClosing() {
        RenderDriverImpl current = driver;
        if (current == null) {
            return;
        }
        try {
            current.onHostDeviceClosing();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("LuxLoader: failed to release host device resources (continuing host shutdown)"), e);
        }
    }

    /** Count each host-initiated blitFromTexture call observed by the mixin. */
    @Override
    public void onFrameBlitted() {
        RenderDriverImpl current = driver;
        if (current == null || !current.isInitialized()) {
            return;
        }
        frameCount++;
        if (hostAdapter != null) {
            hostAdapter.clearFrame();
        }
        if (verbose && frameCount % 600 == 0) {
            LOGGER.info(tr("LuxLoader processed {} frames"), frameCount);
        }
    }

    /**
     * Last callback before present. Core retires frame resources, resets descriptors and handles queued
     * readbacks. Pipelines controlling presentation cadence can take over present here.
     */
    @Override
    public void onFrameEnd() {
        RenderDriverImpl current = driver;
        if (current == null || !current.isInitialized()) {
            return;
        }
        try {
            boolean hostGpuIdle = false;
            if (worldGraphSubmitted && current.gpuDevice() != null) {
                if (current.requiresAsyncCaptureRetirement()) {
                    current.diagnostics().metric("gpu.capture.frame-fence", 1.0, "count");
                } else {
                    long waitStart = System.nanoTime();
                    current.gpuDevice().commands().waitIdleAll();
                    current.diagnostics().metric("cpu.host-frame-wait", (System.nanoTime() - waitStart) / 1_000_000.0, "ms");
                    hostGpuIdle = true;
                }
                worldGraphSubmitted = false;
            }
            current.onFrameEnd(hostGpuIdle);
            dev.luxloader.mc.ui.LuxLoaderNotifications.showPending(current);
        } catch (RuntimeException e) {
            LOGGER.warn(tr("Error finishing the frame"), e);
        }
    }

    /** Mark a forwarded present so the mixin allows the real game implementation to run. */
    private static void markHooksInstalled(RenderDriverImpl current) {
        if (hooksInstalled) {
            return;
        }
        MinecraftHostAdapter adapter = hostAdapter;
        if (adapter == null) {
            // Adapter installation registers initial capabilities; a later callback records observed hook execution.
            return;
        }
        hooksInstalled = true;
        adapter.markHooksInstalled(true);
        adapter.registerCapabilities(current::registerCapability);
    }

    private static void ensureHostAdapter(RenderDriverImpl current, Object windowSurface) {
        if (hostAdapter != null || adapterAttempted) {
            return;
        }
        adapterAttempted = true;
        try {
            if (windowSurface == null) {
                adapterFailure = tr("Minecraft instance or windowSurface is not available yet");
                return;
            }

            MinecraftGraphicsAccess graphics = bridge == null
                    ? MinecraftGraphicsAccess.createDefault(verbose)
                    : MinecraftGraphicsAccess.create(bridge.mapping(), bridge.gameClassLoader(), verbose);

            MinecraftHostAdapter adapter = MinecraftHostAdapter.create(windowSurface, graphics,
                    "NeoForge", gameVersion.isBlank() ? tr("Unknown version") : gameVersion);
            if (adapter == null) {
                adapterFailure = tr("windowSurface is null");
                return;
            }
            hostAdapter = adapter;
            current.attachHostAdapter(adapter);

            HostAdapter.HostDevice device = adapter.device();
            if (device == null) {
                adapterFailure = adapter.describe();
                adapter.registerCapabilities(current::registerCapability);
                LOGGER.info(tr("Host adapter installed, but the game device is unavailable: {}"), adapterFailure);
                return;
            }

            boolean attached = current.attachDevice(device);
            adapter.registerCapabilities(current::registerCapability);
            if (attached) {
                LOGGER.info(tr("Attached game graphics device: {}"), device.describe());
                if (current.gpuDevice() != null) {
                    LOGGER.info(tr("Device capabilities: {} ({}), {} extensions"),
                            current.gpuDevice().capabilities().deviceName(),
                            current.gpuDevice().capabilities().apiVersionString(),
                            current.gpuDevice().capabilities().extensions().size());
                }
            } else {
                adapterFailure = tr("Core rejected device attachment; see loader diagnostics");
                LOGGER.warn(tr("Could not attach the game device: {}"), adapterFailure);
            }
        } catch (RuntimeException | LinkageError e) {
            adapterFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOGGER.warn(tr("Host adapter installation failed; LuxLoader remains idle"), e);
        }
    }

    /**
     * Get the current window surface through mc-adapter reflection. Keeping version-sensitive access in
     * the adapter allows mapping updates without recompiling this mod entry point.
     */
    private static Object currentWindowSurface() {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", false,
                    LuxLoaderNeoForgeClient.class.getClassLoader());
            var getInstance = minecraftClass.getMethod("getInstance");
            Object minecraft = getInstance.invoke(null);
            if (minecraft == null) {
                return null;
            }
            var windowSurface = minecraftClass.getMethod("windowSurface");
            return windowSurface.invoke(minecraft);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    // Diagnostic probes and unattended verification.

    private static final long AUTO_QUIT_NANOS = readAutoQuitNanos();

    private static final long START_NANOS = System.nanoTime();

    private static boolean autoQuitRequested;

    private static long readAutoQuitNanos() {
        try {
            String raw = System.getProperty("luxloader.test.autoQuitSeconds", "0");
            long seconds = Long.parseLong(raw.trim());
            return seconds <= 0L ? 0L : seconds * 1_000_000_000L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static void maybeAutoQuit() {
        if (AUTO_QUIT_NANOS == 0L || autoQuitRequested) {
            return;
        }
        if (System.nanoTime() - START_NANOS < AUTO_QUIT_NANOS) {
            return;
        }
        autoQuitRequested = true;
        try {
            net.minecraft.client.Minecraft.getInstance().stop();
            LOGGER.info("LuxLoader: auto-quit requested after {}s (unattended verification)",
                    AUTO_QUIT_NANOS / 1_000_000_000L);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("LuxLoader: auto-quit failed", e);
        }
    }

    // Pipeline selector entry. Return to the event's original video settings screen on close.

    /** Add a pipeline button beside Done in the video settings footer. */
    private void onScreenInit(net.neoforged.neoforge.client.event.ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof VideoSettingsScreen video)) {
            return;
        }
        // Keep both actions in the vanilla footer instead of covering the scrolling options list.
        for (var child : video.children()) {
            if (child instanceof Button done && CommonComponents.GUI_DONE.equals(done.getMessage())) {
                int buttonWidth = 98;
                int gap = 8;
                int left = (video.width - buttonWidth * 2 - gap) / 2;
                done.setX(left + buttonWidth + gap);
                done.setWidth(buttonWidth);
                event.addListener(Button.builder(
                                net.minecraft.network.chat.Component.literal(tr("Rendering Pipelines")),
                                b -> openPipelineScreen(video))
                        .bounds(left, done.getY(), buttonWidth, done.getHeight()).build());
                break;
            }
        }
    }

    /** Open the selector; parent is the return destination. */
    private static void openPipelineScreen(Screen parent) {
        try {
            net.minecraft.client.Minecraft.getInstance()
                    .setScreenAndShow(new dev.luxloader.mc.ui.LuxLoaderPipelinesScreen(parent, driver));
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Failed to open the pipeline selector"), e);
        }
    }

    // Shutdown.

    private void shutdownQuietly() {
        // Use stderr shutdown heartbeats as in Fabric. Diagnostic reports are written only during shutdown and may never reach disk if native shutdown crashes first.
        shutdownTrace("begin");
        try {
            if (driver != null) {
                driver.exportDiagnostics();
            }
            shutdownTrace("diagnostics exported");
        } catch (RuntimeException e) {
            LOGGER.debug(tr("Could not export diagnostics (ignored)"), e);
        }
        try {
            if (driver != null) {
                driver.close();
            }
            shutdownTrace("driver closed");
        } catch (RuntimeException e) {
            LOGGER.warn(tr("Error shutting down LuxLoader"), e);
        } finally {
            driver = null;
        }
        shutdownTrace("done");
    }

    /**
     * Write and force each shutdown heartbeat to disk. Standard streams can lose messages during JVM exit;
     * this file records the last completed step even when no hs_err or diagnostic report survives.
     */
    private static void shutdownTrace(String step) {
        try {
            java.nio.file.Path file = FMLPaths.GAMEDIR.get()
                    .resolve("luxloader").resolve("shutdown-trace.log");
            java.nio.file.Files.createDirectories(file.getParent());
            byte[] line = (java.time.Instant.now() + " pid=" + ProcessHandle.current().pid()
                    + " " + step + System.lineSeparator())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.APPEND)) {
                channel.write(java.nio.ByteBuffer.wrap(line));
                channel.force(true);
            }
        } catch (Throwable ignored) {
            // Observability must never become another failure point.
        }
    }

    /** Loader instance, possibly null. */
    public static RenderDriverImpl driver() {
        return driver;
    }

    /** Minecraft bridge, which may be null. */
    public static MinecraftBridge bridge() {
        return bridge;
    }

    /** Whether the observed backend is Vulkan. */
    public static boolean isVulkanBackend() {
        return vulkanBackend;
    }

    /** Read verbose logging before loader initialization. */
    private static boolean readVerbose(Path configDir) {
        try {
            Path file = configDir.resolve("luxloader.json");
            if (!java.nio.file.Files.exists(file)) {
                return false;
            }
            String text = java.nio.file.Files.readString(file);
            int index = text.indexOf("\"verbose\"");
            return index >= 0
                    && text.substring(index, Math.min(text.length(), index + 32)).contains("true");
        } catch (Exception e) {
            return false;
        }
    }
}
