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

    private RenderHooks() { }

    public static void install(RenderHookHost implementation) {
        host = implementation;
    }

    public static boolean isInstalled() {
        return host != null;
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
