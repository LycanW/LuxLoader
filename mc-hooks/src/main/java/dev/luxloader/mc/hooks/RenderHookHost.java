package dev.luxloader.mc.hooks;

import dev.luxloader.api.pipeline.WorldFramePlan;
import dev.luxloader.api.vulkan.VulkanFeatureSetRequest;

import java.util.List;

/** Loader-neutral callbacks from Minecraft render hooks to Fabric or NeoForge. */
public interface RenderHookHost {
    /** Capture immutable client facts after a game tick; implementations must not invoke plugins here. */
    default void onClientTick(Object minecraft) { }
    /** Drain state batches and invoke plugin listeners at the safe end-of-loop update boundary. */
    default void onClientSafePoint(Object minecraft) { }
    default boolean requiresDynamicGeometry() { return false; }
    default boolean usesPreparedEntityShadows() { return true; }
    /** Choose the world frame before Minecraft adds its prepared draw passes. */
    WorldFramePlan worldFramePlan();

    /** Encode the plugin step while Minecraft executes its world frame graph. */
    void executeWorldPipeline(Object renderTarget);

    /** Optional device features requested by discovered plugins before device creation. */
    List<VulkanFeatureSetRequest> requestedVulkanFeatureSets();

    /** Process pending selection and attach the device on the first present boundary. */
    void onPresentBoundary(Object windowSurface);

    /** Release plugin Vulkan objects before Minecraft destroys its device. */
    void onHostDeviceClosing();

    /** Minecraft finished recording its swapchain blit. */
    void onFrameBlitted();

    /** Called just before Minecraft presents the frame. */
    void onFrameEnd();
}
