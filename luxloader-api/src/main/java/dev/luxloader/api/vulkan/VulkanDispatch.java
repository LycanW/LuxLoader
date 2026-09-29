package dev.luxloader.api.vulkan;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Collects Vulkan initialization requests from plugins. On the Minecraft
 * backend, {@link #requestDeviceFeatureSet(VulkanFeatureSetRequest)} is wired
 * into optional VkDevice feature selection before the game creates its device.
 * Function-provider and instance-extension requests are retained for future
 * backend integration; collecting one does not mean Minecraft applied it.
 */
public interface VulkanDispatch {

    /**
     * Requests a custom Vulkan function provider before instance creation, during PipelinePlugin.onLoad
     * through PluginBootstrap.vulkan().
     * @param provider native library name/path, e.g. sl.interposer, resolved for the platform
     * @param reason diagnostic reason
     * @return false if a higher-priority request already exists
     */
    boolean requestFunctionProvider(String provider, String reason);

    /** Currently requested function provider. */
    Optional<String> requestedProvider();

    /** Whether diagnostics/development force the system loader and disable DLL replacement. */
    void forceRealLoader(boolean force);

    boolean isRealLoaderForced();

    /**
     * Declares required instance extensions before instance creation, typically from vendor SDK queries.
     * The current Minecraft adapter does not forward these requests into host instance creation; check
     * warnings and actual capabilities.
     */
    void requireInstanceExtension(String name);

    /**
     * Compatibility alias for requireInstanceExtension. Despite its name, it has no optional/soft
     * semantics: missing extensions fail in the same way. Plugins wanting fallback behavior must inspect
     * instanceExtensions before deciding whether to declare a requirement.
     */
    default void wantInstanceExtension(String name) {
        requireInstanceExtension(name);
    }

    /** Declares required instance layers such as validation or vendor overlays. */
    void requireInstanceLayer(String name);

    /** Declared instance extensions. */
    Set<String> requiredInstanceExtensions();

    /** Declared instance layers. */
    Set<String> requiredInstanceLayers();

    /**
     * Declares required device extensions, often obtained from SDK physical-device queries. May be called
     * after probing but before device creation. The current Minecraft adapter enables only feature groups
     * declared through requestDeviceFeatureSet.
     */
    void requireDeviceExtension(String name);

    /** Declared device extensions. */
    Set<String> requiredDeviceExtensions();

    /**
     * Declares a required logical-device feature.
     * @param featureName field in VkPhysicalDeviceFeatures or an extension feature structure, e.g.
     * shaderInt64, bufferDeviceAddress or timelineSemaphore
     */
    void requireDeviceFeature(String featureName);

    /** Declared device features. */
    Set<String> requiredDeviceFeatures();

    /** Request a concrete device extension and feature-bit group before VkDevice creation. */
    void requestDeviceFeatureSet(VulkanFeatureSetRequest request);

    /** Groups accepted in the pre-device window. */
    List<VulkanFeatureSetRequest> requestedDeviceFeatureSets();

    /**
     * Enumerated device extension support, empty before device creation. Plugins must separately verify
     * which extensions the host actually enabled.
     */
    Set<String> availableDeviceExtensions();

    /** Records a loader warning, such as a request for an unavailable extension. */
    List<String> warnings();

    /**
     * Registers a callback after instance/device creation and before pipeline instantiation, allowing
     * plugins to resolve vendor entry points with vkGetDeviceProcAddr.
     */
    void onDispatchReady(DispatchCallback callback);

    /** Dispatch-ready callback. */
    interface DispatchCallback {
        void ready(long instanceHandle, long deviceHandle, long physicalDevice, ProcResolver procAddr);
    }

    /** Function resolver. */
    interface ProcResolver {
        /**
         * Resolves a Vulkan function.
         * @param name symbol such as vkCmdPipelineBarrier2
         * @return function address, or zero if unavailable
         */
        long resolve(String name);
    }

    /** Configuration switch forcing the system loader for development. */
    String FORCE_REAL_LOADER_KEY = "vulkan.forceRealLoader";

    /** Diagnostic section title for the current function provider. */
    String DIAGNOSTICS_SECTION = "Vulkan loader entry points";
}
