package dev.luxloader.core.runtime;

import dev.luxloader.api.config.ConfigOption;
import dev.luxloader.api.config.ConfigSchema;

/**
 * Loader configuration is declared separately from plugins but stored in the same file: top-level
 * loader settings and plugin sections.
 */
public final class LoaderConfig {

    public static final String ENABLED = "loader.enabled";
    public static final String AUTO_ACTIVATE = "loader.autoActivate";
    public static final String ACTIVE_PIPELINE = "loader.activePipeline";
    public static final String ALLOW_EXPERIMENTAL = "loader.allowExperimental";
    /** Additional plugin directory beyond <game directory>/luxloader/pipelines/. */
    public static final String PLUGIN_DIRECTORY = "loader.extraPluginDirectory";
    /** Comma-separated disabled pipeline IDs controlled by the UI. */
    public static final String DISABLED_PIPELINES = "loader.disabledPipelines";

    public static final String VERBOSE = "diagnostics.verbose";
    public static final String MAX_LOG_LINES = "diagnostics.maxLogLines";
    public static final String REPORT_DIRECTORY = "diagnostics.reportDirectory";

    public static final String PREFER_DISCRETE_GPU = "device.preferDiscreteGpu";
    public static final String MIN_VULKAN_VERSION = "device.minVulkanVersion";
    public static final String HEADLESS = "device.headless";

    public static final String FORCE_REAL_LOADER = "vulkan.forceRealLoader";
    public static final String CUSTOM_FUNCTION_PROVIDER = "vulkan.customFunctionProvider";
    public static final String VALIDATION_LAYERS = "vulkan.validationLayers";

    private LoaderConfig() {
    }

    /** Loader configuration schema. */
    public static ConfigSchema schema() {
        return ConfigSchema.builder()
                .add(ConfigOption.bool(ENABLED, true)
                        .name("Enable LuxLoader")
                        .describe("When disabled, the loader leaves rendering to the game.")
                        .group("General")
                        .build())
                .add(ConfigOption.bool(AUTO_ACTIVATE, true)
                        .name("Activate Pipeline Automatically")
                        .describe("Activate the highest-priority available pipeline at startup; otherwise select one manually.")
                        .group("General")
                        .build())
                .add(ConfigOption.text(ACTIVE_PIPELINE, "")
                        .name("Preferred Pipeline")
                        .describe("Enter a GpuId (for example dev.luxloader.example:spatial-upscale/pipeline). Leave empty to select by priority.")
                        .group("General")
                        .build())
                .add(ConfigOption.bool(ALLOW_EXPERIMENTAL, false)
                        .name("Allow Experimental Pipelines")
                        .describe("Include pipelines marked experimental when selecting a pipeline.")
                        .group("General")
                        .build())
                .add(ConfigOption.text(PLUGIN_DIRECTORY, "")
                        .name("Additional Plugin Directory")
                        .describe("Scan an additional directory besides <game directory>/luxloader/pipelines/. Use an absolute path or a path relative to the content directory; leave empty to disable.")
                        .group("General")
                        .build())
                .add(ConfigOption.text(DISABLED_PIPELINES, "")
                        .name("Disabled Pipelines")
                        .describe("Comma-separated pipeline IDs, also updated by the UI's Disable control. Manual edits are supported; empty enables all pipelines.")
                        .group("General")
                        .build())

                .add(ConfigOption.bool(VERBOSE, false)
                        .name("Verbose Logging")
                        .describe("Log frame and probe details for troubleshooting; normally keep disabled.")
                        .group("Diagnostics")
                        .build())
                .add(ConfigOption.integer(MAX_LOG_LINES, 4000)
                        .name("Buffered Log Lines")
                        .describe("Number of in-memory log lines included in diagnostic exports.")
                        .range(200, 100000)
                        .step(200)
                        .group("Diagnostics")
                        .build())
                .add(ConfigOption.text(REPORT_DIRECTORY, "reports")
                        .name("Report Directory")
                        .describe("Diagnostic export directory relative to config/luxloader/.")
                        .group("Diagnostics")
                        .build())

                .add(ConfigOption.bool(PREFER_DISCRETE_GPU, true)
                        .name("Prefer Discrete GPU")
                        .describe("Prefer a discrete graphics card on systems with multiple GPUs.")
                        .restartRequired()
                        .group("Device")
                        .build())
                .add(ConfigOption.text(MIN_VULKAN_VERSION, "1.2")
                        .name("Minimum Vulkan Version")
                        .describe("For example 1.2. Older devices are rejected and the loader remains idle.")
                        .restartRequired()
                        .group("Device")
                        .build())
                .add(ConfigOption.bool(HEADLESS, false)
                        .name("Headless Mode")
                        .describe("Skip window resources for automated tests and offline rendering.")
                        .restartRequired()
                        .group("Device")
                        .build())

                .add(ConfigOption.bool(FORCE_REAL_LOADER, false)
                        .name("Force System Vulkan Loader")
                        .describe("Ignore vendor SDK function-source overrides so Khronos validation works correctly. Features such as DLSS that require an override become unavailable.")
                        .restartRequired()
                        .group("Vulkan")
                        .build())
                .add(ConfigOption.text(CUSTOM_FUNCTION_PROVIDER, "")
                        .name("Custom Function Source")
                        .describe("For example sl.interposer for DLSS. Empty uses the system loader. This is a default; plugins may still request an override during onLoad.")
                        .restartRequired()
                        .group("Vulkan")
                        .build())
                .add(ConfigOption.bool(VALIDATION_LAYERS, false)
                        .name("Enable Validation Layers")
                        .describe("Enable VK_LAYER_KHRONOS_validation for development; this significantly reduces performance.")
                        .restartRequired()
                        .group("Vulkan")
                        .build())
                .build();
    }
}
