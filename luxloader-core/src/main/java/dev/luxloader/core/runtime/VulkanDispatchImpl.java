package dev.luxloader.core.runtime;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.vulkan.VulkanDispatch;
import dev.luxloader.api.vulkan.VulkanFeatureSetRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Collects Vulkan initialization requests at their valid phases: function providers and instance
 * extensions before instance creation, device extensions/features before logical device creation. The
 * Minecraft adapter currently forwards requestDeviceFeatureSet; other requests do not automatically
 * modify host instance/provider creation. Late requests produce warnings. After attachment, inspect
 * actually enabled extensions before capability decisions.
 */
public final class VulkanDispatchImpl implements VulkanDispatch {

    /** Current initialization phase. */
    public enum Stage {
        /** Before instance creation: all requests accepted. */
        BEFORE_INSTANCE,
        /** After instance, before device: device requests only. */
        BEFORE_DEVICE,
        /** After device creation: no further requests accepted. */
        AFTER_DEVICE
    }

    private final List<String> warnings = new ArrayList<>();
    private final Set<String> requiredInstanceExtensions = new LinkedHashSet<>();
    private final Set<String> requiredInstanceLayers = new LinkedHashSet<>();
    private final Set<String> requiredDeviceExtensions = new LinkedHashSet<>();
    private final Set<String> requiredDeviceFeatures = new LinkedHashSet<>();
    private final List<VulkanFeatureSetRequest> deviceFeatureSets = new ArrayList<>();
    private final List<DispatchCallback> dispatchCallbacks = new ArrayList<>();

    private String requestedProvider = "";
    private String providerReason = "";
    private boolean forceRealLoader;
    private Set<String> availableDeviceExtensions = Set.of();
    private Stage stage = Stage.BEFORE_INSTANCE;
    private boolean dispatchNotified;

    @Override
    public boolean requestFunctionProvider(String provider, String reason) {
        if (provider == null || provider.isBlank()) {
            warnings.add(tr("requestFunctionProvider received an empty library name; ignored"));
            return false;
        }
        if (stage != Stage.BEFORE_INSTANCE) {
            // Instance creation fixes the Vulkan function provider.
            warnings.add(tr("Vulkan function provider request '") + provider + tr("' arrived too late (phase ") + stage
                    + tr("). Select the function provider before vkCreateInstance; ")
                    + tr("request ignored. Move it to PipelinePlugin#onLoad."));
            return false;
        }
        if (forceRealLoader) {
            warnings.add(tr("System loader is forced; function provider request '") + provider + tr("' ignored")
                    + tr(" (forceRealLoader is a troubleshooting option that prevents vendor SDK interception)"));
            return false;
        }
        if (!requestedProvider.isEmpty() && !requestedProvider.equals(provider)) {
            warnings.add(tr("A function provider is already requested: '") + requestedProvider + "'（" + providerReason
                    + tr("); request '") + provider + tr("' ignored. Only one provider is permitted per process."));
            return false;
        }
        requestedProvider = provider;
        providerReason = reason == null ? "" : reason;
        return true;
    }

    @Override
    public Optional<String> requestedProvider() {
        return requestedProvider.isEmpty() ? Optional.empty() : Optional.of(requestedProvider);
    }

    @Override
    public void forceRealLoader(boolean force) {
        if (force && !requestedProvider.isEmpty()) {
            warnings.add(tr("forceRealLoader(true) overrides function provider request '") + requestedProvider + "'；"
                    + tr("Vendor SDK interception is disabled; Khronos validation can operate normally"));
            requestedProvider = "";
            providerReason = "";
        }
        this.forceRealLoader = force;
    }

    @Override
    public boolean isRealLoaderForced() {
        return forceRealLoader;
    }

    @Override
    public void requireInstanceExtension(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (stage != Stage.BEFORE_INSTANCE) {
            warnings.add(tr("Instance extension request '") + name + tr("' arrived too late (phase ") + stage
                    + tr("). Move it to PipelinePlugin#onLoad."));
            return;
        }
        requiredInstanceExtensions.add(name);
    }

    @Override
    public void requireInstanceLayer(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (stage != Stage.BEFORE_INSTANCE) {
            warnings.add(tr("Instance layer request '") + name + tr("' arrived too late (phase ") + stage
                    + tr("). Move it to PipelinePlugin#onLoad."));
            return;
        }
        requiredInstanceLayers.add(name);
    }

    @Override
    public void requireDeviceExtension(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (stage == Stage.AFTER_DEVICE) {
            warnings.add(tr("Device extension request '") + name + tr("' arrived after device creation. ")
                    + tr("Move it to PipelinePlugin#probe."));
            return;
        }
        requiredDeviceExtensions.add(name);
    }

    @Override
    public void requireDeviceFeature(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (stage == Stage.AFTER_DEVICE) {
            warnings.add(tr("Device feature request '") + name + tr("' arrived after device creation. ")
                    + tr("Move it to PipelinePlugin#probe."));
            return;
        }
        requiredDeviceFeatures.add(name);
    }

    @Override
    public Set<String> requiredInstanceExtensions() {
        return Collections.unmodifiableSet(requiredInstanceExtensions);
    }

    @Override
    public Set<String> requiredInstanceLayers() {
        return Collections.unmodifiableSet(requiredInstanceLayers);
    }

    @Override
    public Set<String> requiredDeviceExtensions() {
        return Collections.unmodifiableSet(requiredDeviceExtensions);
    }

    @Override
    public Set<String> requiredDeviceFeatures() {
        return Collections.unmodifiableSet(requiredDeviceFeatures);
    }

    @Override
    public void requestDeviceFeatureSet(VulkanFeatureSetRequest request) {
        if (request == null) return;
        if (stage == Stage.AFTER_DEVICE) {
            warnings.add(tr("Device feature-set request '") + request.name() + tr("' arrived after device creation"));
            return;
        }
        if (!deviceFeatureSets.contains(request)) deviceFeatureSets.add(request);
    }

    @Override
    public List<VulkanFeatureSetRequest> requestedDeviceFeatureSets() {
        return List.copyOf(deviceFeatureSets);
    }

    @Override
    public Set<String> availableDeviceExtensions() {
        return availableDeviceExtensions;
    }

    @Override
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    @Override
    public void onDispatchReady(DispatchCallback callback) {
        if (callback == null) {
            return;
        }
        if (dispatchNotified) {
            // Invoke late callbacks immediately if dispatch is already ready.
            warnings.add(tr("onDispatchReady registered after device initialization; invoked immediately. ")
                    + tr("Register during onLoad/probe for deterministic ordering"));
        }
        dispatchCallbacks.add(callback);
    }

    // Loader internals.

    /** Advances to instance-created/device-pending. */
    public void advanceToBeforeDevice() {
        this.stage = Stage.BEFORE_DEVICE;
    }

    /** Creates the instance and reports available extensions for diagnostics. */
    public void reportInstanceCreated(Set<String> actuallyEnabled) {
        for (String requested : requiredInstanceExtensions) {
            if (!actuallyEnabled.contains(requested)) {
                warnings.add(tr("Requested instance extension '") + requested + tr("' was not enabled")
                        + tr(" (unsupported by the driver or misspelled)"));
            }
        }
    }

    /** Supplies physical-device extension support before logical device creation. */
    public void reportAvailableDeviceExtensions(Set<String> available) {
        this.availableDeviceExtensions = available == null ? Set.of() : Set.copyOf(available);
        for (String requested : requiredDeviceExtensions) {
            if (!availableDeviceExtensions.contains(requested)) {
                warnings.add(tr("Requested device extension '") + requested + tr("' is unsupported by the physical device; ")
                        + tr("dependent functionality is unavailable and pipelines should fall back"));
            }
        }
    }

    /** Advances the phase and invokes ready callbacks after device creation. */
    public void notifyDispatchReady(long instance, long device, long physicalDevice,
                                    ProcResolver resolver) {
        this.stage = Stage.AFTER_DEVICE;
        this.dispatchNotified = true;
        for (DispatchCallback callback : dispatchCallbacks) {
            try {
                callback.ready(instance, device, physicalDevice, resolver);
            } catch (RuntimeException e) {
                warnings.add(tr("Dispatch-ready callback threw: ") + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
        }
        dispatchCallbacks.clear();
    }

    /** Current phase. */
    public Stage stage() {
        return stage;
    }

    /** Formats a diagnostic section. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(tr("  Function provider: "))
                .append(requestedProvider.isEmpty()
                        ? (forceRealLoader ? tr("VulkanDispatchImpl.7330d51819", "System loader (forced)") : tr("System loader"))
                        : requestedProvider + "（" + providerReason + "）")
                .append(System.lineSeparator());
        sb.append(tr("  Instance extensions: ")).append(requiredInstanceExtensions.isEmpty()
                ? tr("VulkanDispatchImpl.f4b39d9b45", "(none)") : String.join(", ", requiredInstanceExtensions)).append(System.lineSeparator());
        sb.append(tr("  Instance layers: ")).append(requiredInstanceLayers.isEmpty()
                ? tr("VulkanDispatchImpl.f4b39d9b45", "(none)") : String.join(", ", requiredInstanceLayers)).append(System.lineSeparator());
        sb.append(tr("  Device extensions: ")).append(requiredDeviceExtensions.isEmpty()
                ? tr("VulkanDispatchImpl.f4b39d9b45", "(none)") : String.join(", ", requiredDeviceExtensions)).append(System.lineSeparator());
        sb.append(tr("  Device features: ")).append(requiredDeviceFeatures.isEmpty()
                ? tr("VulkanDispatchImpl.f4b39d9b45", "(none)") : String.join(", ", requiredDeviceFeatures)).append(System.lineSeparator());
        if (!warnings.isEmpty()) {
            sb.append(tr("  Warnings (")).append(warnings.size()).append("):").append(System.lineSeparator());
            for (String warning : warnings) {
                sb.append("    - ").append(warning).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    /** Whether unresolved warnings exist. */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
