package dev.luxloader.core.runtime;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.LuxMod;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.diag.Diagnostics;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.nativebridge.NativeBridge;
import dev.luxloader.api.plugin.PluginBootstrap;
import dev.luxloader.api.pipeline.PipelineSettings;
import dev.luxloader.api.vulkan.VulkanDispatch;

import java.util.List;
import java.util.function.Supplier;

/**
 * PluginBootstrap implementation enforcing device readiness. device()/capabilities() fail with
 * explicit guidance before readiness; plugins can check deviceReady() first.
 */
public final class PluginBootstrapImpl implements PluginBootstrap {

    private final HostServicesImpl host;
    private final VulkanDispatchImpl vulkan;
    private final NativeBridge nativeBridge;
    private final Supplier<GpuDevice> deviceSupplier;
    private final Supplier<GpuCapabilities> capabilitiesSupplier;
    private final Supplier<List<String>> loadedPluginIds;
    private final String gameVersion;
    private final java.nio.file.Path dataDirectory;

    public PluginBootstrapImpl(HostServicesImpl host,
                               VulkanDispatchImpl vulkan,
                               NativeBridge nativeBridge,
                               Supplier<GpuDevice> deviceSupplier,
                               Supplier<GpuCapabilities> capabilitiesSupplier,
                               Supplier<List<String>> loadedPluginIds,
                               String gameVersion) {
        this(host, vulkan, nativeBridge, deviceSupplier, capabilitiesSupplier, loadedPluginIds,
                gameVersion, null);
    }

    /** @param dataDirectory package data directory, or null for classpath/nested-JAR plugins */
    public PluginBootstrapImpl(HostServicesImpl host,
                               VulkanDispatchImpl vulkan,
                               NativeBridge nativeBridge,
                               Supplier<GpuDevice> deviceSupplier,
                               Supplier<GpuCapabilities> capabilitiesSupplier,
                               Supplier<List<String>> loadedPluginIds,
                               String gameVersion,
                               java.nio.file.Path dataDirectory) {
        this.host = host;
        this.vulkan = vulkan;
        this.nativeBridge = nativeBridge;
        this.deviceSupplier = deviceSupplier;
        this.capabilitiesSupplier = capabilitiesSupplier;
        this.loadedPluginIds = loadedPluginIds;
        this.gameVersion = gameVersion == null ? "" : gameVersion;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public java.util.Optional<java.nio.file.Path> dataDirectory() {
        return java.util.Optional.ofNullable(dataDirectory);
    }

    @Override
    public HostServicesImpl host() {
        return host;
    }

    @Override
    public LuxMod mod() {
        return host.mod();
    }

    @Override
    public VulkanDispatch vulkan() {
        return vulkan;
    }

    @Override
    public NativeBridge nativeBridge() {
        return nativeBridge;
    }

    @Override
    public GpuCapabilities capabilities() {
        GpuCapabilities caps = capabilitiesSupplier.get();
        if (caps == null) {
            throw new IllegalStateException(
                    tr("Device is not ready; capability snapshot unavailable. ")
                            + tr("Check deviceReady() first or move this code to PipelinePlugin#probe. ")
                            + tr("(onLoad runs before GPU device creation)"));
        }
        return caps;
    }

    @Override
    public GpuDevice device() {
        GpuDevice device = deviceSupplier.get();
        if (device == null) {
            throw new IllegalStateException(
                    tr("Device is not ready. Check deviceReady() first ")
                            + tr("or move this code to PipelinePlugin#probe. ")
                            + tr("(onLoad runs before GPU device creation)"));
        }
        return device;
    }

    @Override
    public boolean deviceReady() {
        return deviceSupplier.get() != null;
    }

    @Override
    public List<String> loadedPluginIds() {
        return loadedPluginIds.get();
    }

    @Override
    public String loaderVersion() {
        return host.loaderVersion();
    }

    @Override
    public String gameVersion() {
        return gameVersion;
    }

    @Override
    public void capability(String id, CapabilityLevel level, String detail) {
        host.registerCapability(new dev.luxloader.api.capability.CapabilityDescriptor(
                id, level, id, mod().id().toString(), detail, java.util.Map.of(), System.nanoTime()));
    }

    @Override
    public Diagnostics diagnostics() {
        return host.diagnostics();
    }

    @Override
    public PipelineSettings settings() {
        return host.settings();
    }
}
