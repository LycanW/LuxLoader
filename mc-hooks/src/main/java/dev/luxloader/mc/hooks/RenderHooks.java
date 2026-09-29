package dev.luxloader.mc.hooks;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.pipeline.WorldFramePlan;
import dev.luxloader.api.vulkan.VulkanFeatureSetRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Shared Minecraft mixins call this bridge; loaders install their own host. */
public final class RenderHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger("LuxLoader/Hooks");
    private static volatile RenderHookHost host;
    private static final ThreadLocal<Boolean> applyingServerHotbarSlot = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Integer> continueBlockBreakDepth = new ThreadLocal<>();

    private RenderHooks() { }

    public static void install(RenderHookHost implementation) {
        host = implementation;
    }

    public static boolean isInstalled() {
        return host != null;
    }

    public static void onClientTick(Object minecraft) {
        RenderHookHost current = host;
        if (current == null) return;
        try {
            current.onClientTick(minecraft);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Could not capture client state; retaining the last valid observation"), e);
        }
    }

    public static void onClientSafePoint(Object minecraft) {
        RenderHookHost current = host;
        if (current == null) return;
        try {
            current.onClientSafePoint(minecraft);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Could not dispatch client state at the safe update point"), e);
        }
    }

    public static boolean wantsClientBehaviorCapture() {
        RenderHookHost current = host;
        if (current == null) return false;
        try {
            return current.wantsClientBehaviorCapture();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Could not check client behavior event demand"), e);
            return false;
        }
    }

    public static void onClientBehaviorSignal(Object minecraft, ClientBehaviorSignal signal) {
        RenderHookHost current = host;
        if (current == null) return;
        try {
            current.onClientBehaviorSignal(minecraft, signal);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Could not capture a client behavior event"), e);
        }
    }

    /** Marks the exact Inventory setter invoked by a server hotbar packet, preventing duplicate observation. */
    public static void beginServerHotbarSlotNotification() {
        applyingServerHotbarSlot.set(true);
    }

    public static void endServerHotbarSlotNotification() {
        applyingServerHotbarSlot.remove();
    }

    public static boolean isApplyingServerHotbarSlotNotification() {
        return applyingServerHotbarSlot.get();
    }

    /** Opens the exact continueDestroyBlock call scope so its nested start is distinguishable from a user start. */
    public static void beginContinueBlockBreak() {
        Integer depth = continueBlockBreakDepth.get();
        continueBlockBreakDepth.set(depth == null ? 1 : depth + 1);
    }

    /** Closes one continueDestroyBlock scope on its normal return. */
    public static void endContinueBlockBreak() {
        Integer depth = continueBlockBreakDepth.get();
        if (depth == null || depth <= 1) continueBlockBreakDepth.remove();
        else continueBlockBreakDepth.set(depth - 1);
    }

    public static boolean isContinuingBlockBreak() {
        Integer depth = continueBlockBreakDepth.get();
        return depth != null && depth > 0;
    }

    public static boolean requiresDynamicGeometry() {
        RenderHookHost current = host;
        return current != null && current.requiresDynamicGeometry();
    }

    public static boolean usesPreparedEntityShadows() {
        RenderHookHost current = host;
        return current == null || current.usesPreparedEntityShadows();
    }

    public static WorldFramePlan worldFramePlan() {
        RenderHookHost current = host;
        if (current == null) return null;
        try {
            return current.worldFramePlan();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Could not plan the plugin world frame; retaining host rendering"), e);
            return null;
        }
    }

    public static void executeWorldPipeline(Object renderTarget) {
        RenderHookHost current = host;
        if (current == null) return;
        try {
            current.executeWorldPipeline(renderTarget);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Plugin world stage failed"), e);
        }
    }

    public static void onPresentBoundary(Object windowSurface) {
        RenderHookHost current = host;
        if (current == null) return;
        try {
            current.onPresentBoundary(windowSurface);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Present boundary handling failed"), e);
        }
    }

    public static void onHostDeviceClosing() {
        RenderHookHost current = host;
        if (current == null) return;
        try {
            current.onHostDeviceClosing();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Plugin device resource cleanup failed"), e);
        }
    }

    public static void onFrameBlitted() {
        RenderHookHost current = host;
        if (current == null) return;
        try {
            current.onFrameBlitted();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Could not finish frame recording"), e);
        }
    }

    public static void onFrameEnd() {
        RenderHookHost current = host;
        if (current == null) return;
        try {
            current.onFrameEnd();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Could not finish the plugin frame"), e);
        }
    }

    public static List<VulkanFeatureSetRequest> requestedVulkanFeatureSets() {
        RenderHookHost current = host;
        if (current == null) return List.of();
        try {
            return current.requestedVulkanFeatureSets();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn(tr("Could not read plugin Vulkan feature requests"), e);
            return List.of();
        }
    }

    public static void noteVulkanFeatureSetsOffered(int count) {
        LOGGER.info(tr("[LuxLoader] Offered {} optional plugin Vulkan feature sets to Minecraft"), count);
    }

    public static void noteVulkanFeatureSetFailed(String name, Throwable error) {
        LOGGER.warn(tr("[LuxLoader] Could not offer plugin Vulkan feature set {}"), name, error);
    }
}
