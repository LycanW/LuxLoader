package dev.luxloader.api.plugin;

import dev.luxloader.api.LuxMod;
import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.config.ConfigSchema;
import dev.luxloader.api.diag.Diagnostics;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.nativebridge.NativeBridge;
import dev.luxloader.api.vulkan.VulkanDispatch;

import java.util.List;

/**
 * Context valid only during plugin loading/probing. HostServices lasts longer; this context also
 * controls early Vulkan loading, native paths and capability registration. During onLoad, register
 * pipelines/configuration/native paths and requestFunctionProvider before instance creation. During
 * probe, inspect device/capabilities, register actual availability and declare device extensions.
 */
public interface PluginBootstrap {

    /** Host registration, configuration and diagnostic services. */
    HostServices host();

    /** This plugin's metadata. */
    LuxMod mod();

    /**
     * Vulkan loading control. requestFunctionProvider must run in onLoad before instance creation fixes
     * the provider. Device extension/feature declarations may occur as late as probe.
     */
    VulkanDispatch vulkan();

    /** Native library bridge. */
    NativeBridge nativeBridge();

    /**
     * Device capability snapshot.
     * @throws IllegalStateException before the device is ready, including onLoad
     */
    GpuCapabilities capabilities();

    /**
     * GPU device.
     * @throws IllegalStateException before device readiness
     */
    GpuDevice device();

    /** Whether the device is ready, allowing safe GPU access checks during onLoad. */
    boolean deviceReady();

    /**
     * Register a capability with this plugin as provider, equivalent to host().registerCapability with
     * automatic provider metadata.
     */
    default void capability(String id, CapabilityLevel level, String detail) {
        host().registerCapability(new CapabilityDescriptor(id, level, id, mod().id().toString(),
                detail, java.util.Map.of(), System.nanoTime()));
    }

    /** Register boolean capability availability. */
    default void capability(String id, boolean available, String detail) {
        capability(id, CapabilityLevel.of(available), detail);
    }

    /** Declares a plugin-provided native library directory relative to the JAR. */
    default void nativePath(String relativePath) {
        host().registerNativeLibraryPath(relativePath);
    }

    /** Declares configuration sections. */
    default ConfigSchema declareConfig() {
        return host().declareConfig();
    }

    /** Reads this plugin's configuration. */
    default dev.luxloader.api.pipeline.PipelineSettings settings() {
        return host().settings();
    }

    /** Diagnostic output. */
    default Diagnostics diagnostics() {
        return host().diagnostics();
    }

    /** Other registered plugin IDs for dependency checks and cooperation. */
    List<String> loadedPluginIds();

    /**
     * Plugin data directory. Directory installations use their directory under luxloader/pipelines; single
     * JARs use the containing directory; classpath/nested JAR plugins have no disk directory and should
     * use classpath resources. Disk shader sources can be edited and reloaded without repackaging. The
     * user owns this directory; the loader only reads it. Use host().cacheDirectory() for writable
     * storage.
     */
    default java.util.Optional<java.nio.file.Path> dataDirectory() {
        return java.util.Optional.empty();
    }

    /** Loader version. */
    String loaderVersion();

    /** Game version, or empty when unknown. */
    String gameVersion();
}
