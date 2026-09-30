package dev.luxloader.mc.fabric;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.plugin.RenderDriver;
import dev.luxloader.core.runtime.RenderDriverImpl;
import dev.luxloader.api.host.HostAdapter;
import dev.luxloader.mc.MinecraftBridge;
import dev.luxloader.mc.MinecraftBehaviorEventAccess;
import dev.luxloader.mc.MinecraftClientStateAccess;
import dev.luxloader.mc.MinecraftGraphicsAccess;
import dev.luxloader.mc.MinecraftHostAdapter;
import dev.luxloader.mc.hooks.ClientBehaviorSignal;
import dev.luxloader.mc.hooks.RenderHookHost;
import dev.luxloader.mc.hooks.RenderHooks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Fabric client entry point. Discovers plugins and configuration even when Vulkan is unavailable,
 * probes the host, registers observed capabilities, and drives the loader through the shared
 * RenderHooks bridge. Non-Vulkan hosts remain idle without allocating Vulkan resources. Shared mixins
 * target Minecraft classes and never reference loader-specific types.
 */
public final class LuxLoaderFabricClient implements ClientModInitializer, RenderHookHost {

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
    private static boolean soundBackendAttached;
    private static boolean vulkanBackend;
    private static String gameVersion = "";

    /** Attempt adapter installation once; repeated failures must not stall every frame. */
    private static boolean adapterAttempted;
    /** Adapter installation failure included in diagnostics. */
    private static String adapterFailure = "";

    /** Observed backend description for diagnostics. */
    private static String backendDescription = tr("Unknown");

    /** Processed frame count for diagnostics. */
    private static long frameCount;

    /**
     * Set only after a frame hook actually runs. Optional mixin injections may fail silently, so
     * configuration alone cannot establish host.frame_hook support.
     */
    private static boolean hooksInstalled;

    /** Cache verbose logging before loader initialization for host probes. */
    private static boolean verbose;

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

    @Override
    public void onInitializeClient() {
        // Register the hook host before initialization. Later mixin callbacks remain safe even when initialization fails: uninstalled hooks pass through and installed hooks guard the null driver.
        RenderHooks.install(this);

        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("luxloader");
        gameVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");

        LuxMod mod = LuxMod.builder(ID, "LuxLoader", RenderDriverImpl.VERSION)
                .authors("LuxLoader")
                .description(tr("Rendering pipeline loader for Minecraft's Vulkan backend"))
                .license("MIT")
                .build();

        LOGGER.info(tr("Initializing LuxLoader {} (Minecraft {})"), RenderDriverImpl.VERSION,
                gameVersion.isBlank() ? tr("Unknown version") : gameVersion);

        verbose = readVerbose(configDir);

        try {
            // 1. Probe Minecraft integration points.
            bridge = new MinecraftBridge(mod, verbose);
            bridge.probe();
            vulkanBackend = bridge.isVulkanBackend();
            backendDescription = vulkanBackend ? "Vulkan" : tr("Non-Vulkan or unknown");
            if (verbose) {
                LOGGER.info(tr("Minecraft integration probe:\n{}"), bridge.describe());
            }

            // 2. Initialize the loader. Configuration lives in config/luxloader; plugins, caches and reports live in luxloader next to mods.
            Path gameDir = FabricLoader.getInstance().getGameDir();
            driver = new RenderDriverImpl(configDir, gameDir.resolve("luxloader"));
            driver.setGameVersion(gameVersion);
            // Wait for the host Vulkan device instead of allocating another device and competing for VRAM.
            driver.initialize(RenderDriver.DeviceRequest.attachedToGame());

            // 3. Register observed Minecraft capabilities.
            bridge.registerCapabilities(descriptor ->
                    driver.registerCapability(descriptor));

            // 4. Report status.
            if (!vulkanBackend) {
                LOGGER.info(tr("The active backend is not Vulkan; LuxLoader will remain idle. ")
                        + tr("This is expected when using OpenGL ")
                        + tr("(Mojang marked Vulkan experimental in 26.2 Snapshot 8)."));
            } else if (!driver.deviceReady()) {
                LOGGER.warn(tr("Vulkan detected; waiting for the game device handle. ")
                        + tr("Pipelines activate after the adapter calls attachDevice."));
            } else {
                LOGGER.info(tr("Vulkan backend attached, device: {}"),
                        driver.gpuDevice().capabilities().deviceName());
            }

            LOGGER.info(tr("LuxLoader initialized: {} plugins, {} pipelines, active {}"),
                    driver.loadedPlugins().size(),
                    driver.registeredPipelineCount(),
                    driver.activePipelineId().map(Object::toString).orElse(tr("LuxLoaderFabricClient.c7bcc6d27f", "(none)")));

            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownQuietly, "luxloader-shutdown"));

        } catch (RuntimeException | LinkageError e) {
            // Loader failures must not prevent the game from starting.
            LOGGER.error(tr("LuxLoader initialization failed; rendering will remain unchanged"), e);
            shutdownQuietly();
        }
    }

    private void shutdownQuietly() {
        // Shutdown breadcrumbs need durable file writes. Native crashes may leave neither an hs_err nor a final report; stderr and the parent process pipe can discard later shutdown messages even on a successful exit. A missing done message therefore does not establish that cleanup failed. Use the forced file trace below and compare its PID and timestamp to this run.
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
     * Append a shutdown breadcrumb and force it to disk. Standard streams may close before all shutdown
     * messages are captured. FileChannel.force(true) preserves each completed write across a later crash.
     * Include the PID to distinguish stale runs, and ignore I/O failures so observation cannot break
     * cleanup.
     */
    private static void shutdownTrace(String step) {
        try {
            java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getGameDir().resolve("luxloader").resolve("shutdown-trace.log");
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
            // Diagnostics must not fail shutdown.
        }
    }

    // Frame boundary hooks called by shared mixins.

    private static boolean worldGraphFrame;
    private static boolean worldGraphSubmitted;
    private static int missingWorldFrames;

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
            maybeAutoQuit();
            maybeOpenWorld();
            maybeTeleport();
            maybePlaceBlocks();
            if (!worldGraphFrame) {
                // The menu and the first device-attachment frame still need a
                // boundary to process pending plugin selection changes.
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
     * The host is about to destroy its Vulkan device (injected at VulkanDevice#close HEAD).
     *
     * <p>Must not throw: a failure here would abort the host's own shutdown. It also must
     * not run plugin callbacks -- those stay in the loader close path.
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
            LOGGER.warn("LuxLoader: releasing host-device resources failed (continuing host shutdown)", e);
        }
    }

    // ---------------- unattended verification ----------------

    /**
     * {@code -Dluxloader.test.autoQuitSeconds=N}: stop the client after N seconds.
     *
     * <p>Why: every verification run used to need a human to close the window, because a
     * force-kill skips the graceful shutdown and therefore skips the very code path being
     * verified. This makes runs unattended while still exercising the real shutdown.
     *
     * <p>Verified via javap that {@code Minecraft.stop()} is simply {@code running = false}:
     * the game's own main loop then performs a full graceful shutdown -- the same path as
     * clicking the window close button. One field write, no cleanup, no deadlock risk.
     */
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

    /**
     * Unattended test hook enabled by -Dluxloader.test.openWorld=SAVE_DIRECTORY. Uses
     * Minecraft.createWorldOpenFlows().openWorld on the render thread because quickPlaySingleplayer failed
     * in both vanilla Fabric and modded tests on this client version. This is opt-in test scaffolding,
     * like autoQuitSeconds.
     */
    private static final String TEST_OPEN_WORLD =
            System.getProperty("luxloader.test.openWorld", "");

    /** Wait for client UI initialization before opening the world. */
    private static final long OPEN_WORLD_DELAY_NANOS = 6_000_000_000L;

    private static boolean openWorldRequested;

    private static void maybeOpenWorld() {
        if (TEST_OPEN_WORLD.isBlank() || openWorldRequested) {
            return;
        }
        // Minecraft 26.3 exposes no screen field. Delay after frame callbacks begin, rather than reflecting a nonexistent current-screen field.
        if (System.nanoTime() - START_NANOS < OPEN_WORLD_DELAY_NANOS) {
            return;
        }
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft == null || minecraft.level != null) {
                openWorldRequested = true;
                return;
            }
            openWorldRequested = true;
            LOGGER.info(tr("LuxLoader: requesting world {} (unattended test)"), TEST_OPEN_WORLD);
            // Queue the operation on the render thread; opening a world inside blitFromTexture would interrupt an active frame recording.
            minecraft.execute(() -> {
                try {
                    minecraft.createWorldOpenFlows().openWorld(TEST_OPEN_WORLD,
                            () -> LOGGER.warn(tr("LuxLoader: opening world {} failed (callback)"), TEST_OPEN_WORLD));
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.warn(tr("LuxLoader: exception opening world {}"), TEST_OPEN_WORLD, e);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("LuxLoader: automatic world opening is unavailable"), e);
            openWorldRequested = true;
        }
    }

    /**
     * Opt-in test teleport using -Dluxloader.test.teleport=x,y,z. Loaded chunks follow the player, so
     * moving only the diagnostic camera can leave the traced region empty. Uses verified public
     * Minecraft.player and Entity.snapTo members; teleportTo overloads require server context or
     * TeleportTransition.
     */
    private static final String TEST_TELEPORT =
            System.getProperty("luxloader.test.teleport", "");

    private static boolean teleportDone;

    private static void maybeTeleport() {
        if (TEST_TELEPORT.isBlank() || teleportDone) {
            return;
        }
        String[] parts = TEST_TELEPORT.split(",");
        if (parts.length != 3) {
            return;
        }
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null) {
                return; // The player has not entered a world yet.
            }
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            double z = Double.parseDouble(parts[2].trim());
            teleportDone = true;
            minecraft.execute(() -> {
                try {
                    minecraft.player.snapTo(x, y, z);
                    LOGGER.info(tr("LuxLoader: moved player to ({}, {}, {}) (unattended test)"), x, y, z);
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.warn(tr("LuxLoader: teleport failed"), e);
                }
            });
        } catch (NumberFormatException e) {
            LOGGER.warn(tr("LuxLoader: could not parse -Dluxloader.test.teleport: {}"), TEST_TELEPORT);
            teleportDone = true;
        }
    }

    /** Unattended material fixture: block IDs come from launch arguments; production code has no block-name list. */
    private static final String TEST_PLACE_BLOCKS =
            System.getProperty("luxloader.test.placeBlocks", "");
    private static boolean placeBlocksDone;
    private static long placeBlocksWorldReadyAt;

    private static void maybePlaceBlocks() {
        if (TEST_PLACE_BLOCKS.isBlank() || placeBlocksDone) {
            return;
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null
                || minecraft.getSingleplayerServer() == null) {
            return;
        }
        if (placeBlocksWorldReadyAt == 0L) {
            placeBlocksWorldReadyAt = System.nanoTime();
            return;
        }
        if (System.nanoTime() - placeBlocksWorldReadyAt < 3_000_000_000L) {
            return;
        }
        placeBlocksDone = true;
        var server = minecraft.getSingleplayerServer();
        server.execute(() -> {
            for (String entry : TEST_PLACE_BLOCKS.split(";")) {
                String[] parts = entry.split(",");
                if (parts.length != 4) {
                    LOGGER.warn(tr("LuxLoader: invalid test block argument: {}"), entry);
                    continue;
                }
                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    var id = net.minecraft.resources.Identifier.parse(parts[3].trim());
                    var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
                    if (!registry.containsKey(id)) {
                        LOGGER.warn(tr("LuxLoader: test block does not exist: {}"), id);
                        continue;
                    }
                    boolean placed = server.overworld().setBlock(
                            new net.minecraft.core.BlockPos(x, y, z),
                            registry.getValue(id).defaultBlockState(), 3, 512);
                    LOGGER.info(tr("LuxLoader: placed test block {} at ({},{},{}), result {}"),
                            id, x, y, z, placed);
                } catch (RuntimeException e) {
                    LOGGER.warn(tr("LuxLoader: test block placement failed: {}"), entry, e);
                }
            }
        });
    }

    /**
     * Record an observed frame hook and refresh its dependent capabilities. Initial adapter registration
     * precedes hook execution, so changing a flag alone would leave frame_hook, resize_signal and
     * present_intercept stale. The registry permits later observations at the same or higher support
     * level. Only actual invocation establishes support for an optional injection.
     */
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
            MinecraftGraphicsAccess graphics = bridge == null
                    ? MinecraftGraphicsAccess.createDefault(verbose)
                    : MinecraftGraphicsAccess.create(bridge.mapping(), bridge.gameClassLoader(), verbose);

            MinecraftHostAdapter adapter = MinecraftHostAdapter.create(windowSurface, graphics,
                    "Fabric", gameVersion.isBlank() ? tr("Unknown version") : gameVersion);
            if (adapter == null) {
                adapterFailure = tr("windowSurface is null; the window may not exist yet");
                return;
            }
            hostAdapter = adapter;

            current.attachHostAdapter(adapter);

            HostAdapter.HostDevice device = adapter.device();
            if (device == null) {
                adapterFailure = adapter.describe();
                LOGGER.info(tr("Host adapter installed, but the game device is unavailable: {}"), adapterFailure);
                // Register capabilities even when device attachment is pending so plugins see actual availability.
                adapter.registerCapabilities(current::registerCapability);
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

    /** Final composition completed: clear captured frame inputs and advance the counter. */
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
        // Report every 600 frames to limit log volume.
        if (verbose && frameCount % 600 == 0) {
            LOGGER.info(tr("LuxLoader processed {} frames (backend: {})"), frameCount, backendDescription);
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
                    // Preserve the ordinary frame boundary; capture frames use a queue fence and poll instead.
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

    /** Installed host adapter, or null when unavailable. */
    public static MinecraftHostAdapter hostAdapter() {
        return hostAdapter;
    }

    /** Adapter failure reason, or an empty string on success. */
    public static String adapterFailure() {
        return adapterFailure;
    }

    // Pipeline selection UI entry point.

    /**
     * Open pipeline selection from video settings. The caller must supply the return screen because
     * Minecraft 26.3 has no public current-screen accessor.
     */
    public static void openPipelineScreen(net.minecraft.client.gui.screens.Screen parent) {
        try {
            net.minecraft.client.Minecraft.getInstance()
                    .setScreenAndShow(new dev.luxloader.mc.ui.LuxLoaderPipelinesScreen(parent, driver));
        } catch (RuntimeException | LinkageError e) {
            reportScreenHookFailure(e);
        }
    }

    /** Report UI hook failures without propagating them into the game render loop. */
    public static void reportScreenHookFailure(Throwable error) {
        LOGGER.warn(tr("LuxLoader UI hook failed (ignored)"), error);
    }

    /** Processed frame count for diagnostics. */
    public static long frameCount() {
        return frameCount;
    }

    // Read-only status accessors.

    /** Loader instance, or null when disabled or initialization failed. */
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

    /** Backend description for diagnostics. */
    public static String backendDescription() {
        return backendDescription;
    }

    /** Game version. */
    public static String gameVersion() {
        return gameVersion;
    }

    /**
     * Read verbose mode before loader configuration exists. A minimal text probe supports early backend
     * detection; read failures default to disabled and do not affect correctness.
     */
    private static boolean readVerbose(Path configDir) {
        try {
            Path file = configDir.resolve("luxloader.json");
            if (!java.nio.file.Files.exists(file)) {
                return false;
            }
            String text = java.nio.file.Files.readString(file);
            int index = text.indexOf("\"verbose\"");
            if (index < 0) {
                return false;
            }
            return text.substring(index, Math.min(text.length(), index + 32)).contains("true");
        } catch (Exception e) {
            return false;
        }
    }
}
