package dev.luxloader.api.host;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.gpu.ImageHandle;

import java.util.List;
import java.util.Set;

/**
 * Host adapter contract between a game and the LuxLoader kernel. The kernel handles registration,
 * driving and switching pipelines independently of Minecraft, Fabric or NeoForge. Adapters translate
 * host data into neutral handles, allowing independent tests and other host implementations.
 * <p>Unavailable devices/textures are normal: return null or empty collections instead of throwing
 * across the boundary. Per-frame methods must avoid expensive queries, allocations and locks. Report
 * handles and capabilities as facts; leave rendering and fallback decisions to the kernel and plugins.
 * {@code HostServices} supplies kernel services to plugins; this interface supplies host facts to the
 * kernel.
 */
public interface HostAdapter extends AutoCloseable {
    default dev.luxloader.api.scene.ResourceAccess resources() {
        return dev.luxloader.api.scene.ResourceAccess.EMPTY;
    }

    /** Host name for diagnostics, e.g. Minecraft 26.3 / NeoForge. */
    String hostName();

    /** Graphics backend name, such as Vulkan or OpenGL; empty when unknown. */
    default String backendName() {
        return "";
    }

    /**
     * Capabilities used for requirement validation and negotiation. Null indicates an unavailable device;
     * core remains idle.
     */
    dev.luxloader.api.gpu.GpuCapabilities capabilities();

    /**
     * Extensions actually enabled on the host VkDevice. An empty Optional means
     * the host cannot report them; an Optional containing an empty set means it
     * can report them and none were enabled. Device capability checks must use
     * this list when available, not the physical device's advertised extensions.
     */
    default java.util.Optional<java.util.Set<String>> enabledDeviceExtensions() {
        return java.util.Optional.empty();
    }

    /**
     * Native host graphics device handles. Null means unavailable, for example on OpenGL or before adapter
     * setup; core will not activate pipelines.
     */
    HostDevice device();

    /**
     * Current extracted scene: geometry, camera and environment. This is the data entry point for
     * replacing Minecraft rendering. Extraction belongs to the adapter because it depends on game
     * internals; pipelines decide rendering, culling and sorting. An empty snapshot is valid in
     * menus/loading screens. Return null when extraction itself is unsupported; the kernel reports {@code
     * host.scene_extraction} unavailable and permits only {@code FrameOwnership.SHARED} pipelines.
     */
    default dev.luxloader.api.scene.SceneSnapshot sceneSnapshot() {
        return null;
    }

    /**
     * Cheap per-frame camera and environment state, without rebuilding world geometry.
     * Hosts that only support the older snapshot contract may delegate to it.
     */
    default dev.luxloader.api.scene.SceneSnapshot sceneMetadata() {
        return sceneSnapshot();
    }

    /**
     * Accepted mesh changes, independent of the per-frame camera snapshot.
     * A consumer owns its revision cursor; a reset replaces its entire mesh set.
     * Null means this host has no incremental geometry source.
     */
    default dev.luxloader.api.scene.SceneGeometryFeed sceneGeometryFeed() {
        return null;
    }

    /**
     * Whether neutral world geometry can be extracted. Core rejects exclusive-frame pipelines when this is
     * false because they lack geometry to render.
     */
    default boolean supportsSceneExtraction() {
        return false;
    }

    /**
     * Registers host capability facts using the standard {@link CapabilityDescriptor.Ids} keys.
     * @param sink receives each capability through {@code accept}
     */
    void registerCapabilities(java.util.function.Consumer<CapabilityDescriptor> sink);

    /**
     * Textures available for this frame. Called once per frame; {@link HostFrameTextures#EMPTY} is valid.
     * Missing depth or motion vectors are reported to the pipeline, which chooses its fallback.
     */
    HostFrameTextures frameTextures();

    /** Frame hook integration point. Return an available hook or HostFrameHook.NONE for manual driving. */
    HostFrameHook frameHook();

    /**
     * Acquired swapchain image targeted for presentation. The loader blits {@code frame.final} into it and
     * asks the host to skip its original blit, replacing that operation without adding a second copy. The
     * game's composed {@code frame.color} remains a read-only pipeline input. The adapter resolves the
     * host's current swapchain index; the kernel does not inspect game internals.
     * @param vkImage native {@code VkImage}; zero if not acquired or unavailable on this backend
     * @param vkFormat native format used to validate blit compatibility
     * @param width pixel width
     * @param height pixel height
     */
    record HostPresentImage(long vkImage, int vkFormat, int width, int height) {

        /** Value used when unavailable. */
        public static final HostPresentImage NONE = new HostPresentImage(0L, 0, 0, 0);

        /** Whether usable. */
        public boolean isUsable() {
            return vkImage != 0L && width > 0 && height > 0;
        }

        public String describe() {
            return isUsable()
                    ? "VkImage=0x" + Long.toHexString(vkImage) + " VkFormat=" + vkFormat
                    + " " + width + "x" + height
                    // Do not invent a failure reason here. Missing sizes, allocation failures and exceptions require distinct diagnostics from the caller.
                    : tr("Unavailable");
        }
    }

    /**
     * Current presentation image, queried once per frame before the pipeline's presentation decision.
     * {@link HostPresentImage#NONE} is valid before acquisition or on another backend; the loader then
     * leaves normal game presentation in place.
     */
    default HostPresentImage presentImage() {
        return HostPresentImage.NONE;
    }

    /**
     * Notes recorded during host probing/reflection, including unresolved classes, failed calls, ambiguous
     * candidates and unavailable usage flags. The loader includes these verbatim in host adapter
     * diagnostics so unavailable entry points retain actionable causes. Defaults to an empty list.
     */
    default java.util.List<String> notes() {
        return java.util.List.of();
    }

    /**
     * Why the latest {@link #presentImage()} failed; null or empty on success. Distinguishes missing
     * surfaces, unavailable dimensions, texture creation failures and exceptions that otherwise all return
     * {@link HostPresentImage#NONE}.
     */
    default String presentImageNote() {
        return null;
    }

    /**
     * Release any GPU resource the adapter itself created on the host device.
     *
     * <p>Called from the host-device release hook, i.e. while the device is still alive.
     * Added because validation reported exactly two leaked objects at vkDestroyDevice --
     * our own present texture and its view -- with no release path anywhere.
     *
     * <p>Must not throw: it runs inside the host's shutdown sequence.
     */
    default void releaseHostResources() {
    }

    /** Whether the host device is ready. */
    default boolean isReady() {
        return capabilities() != null && device() != null;
    }

    /** Whether the host reliably signals swapchain size changes. Otherwise core compares dimensions each frame. */
    default boolean hasReliableResizeSignal() {
        return false;
    }

    @Override
    default void close() {
    }

    /**
     * Native host device handles. The kernel forwards raw pointers to its Vulkan wrapper.
     * @param instance native {@code VkInstance}, or zero if unavailable
     * @param physicalDevice native {@code VkPhysicalDevice}, or zero if unavailable
     * @param device native {@code VkDevice}, or zero if unavailable
     * @param ownedByHost if true, the kernel must never destroy this device
     * @param instanceApiVersion API version used to create the host instance, not the physical device
     * maximum
     * @param instanceExtensions extensions actually enabled on the instance
     */
    record HostDevice(long instance, long physicalDevice, long device, boolean ownedByHost,
                      int instanceApiVersion, java.util.Set<String> instanceExtensions) {

        public HostDevice {
            instanceExtensions = instanceExtensions == null ? java.util.Set.of() : java.util.Set.copyOf(instanceExtensions);
        }

        public HostDevice(long instance, long physicalDevice, long device, boolean ownedByHost) {
            this(instance, physicalDevice, device, ownedByHost, 0, java.util.Set.of());
        }

        /** Create the usual host-owned device handle. */
        public static HostDevice ofHost(long instance, long physicalDevice, long device) {
            return new HostDevice(instance, physicalDevice, device, true);
        }

        /** Whether the device handle is usable. */
        public boolean isUsable() {
            return device != 0L;
        }

        /** Ray tracing and extension queries require a physical device handle. */
        public boolean hasPhysicalDevice() {
            return physicalDevice != 0L;
        }

        /** Diagnostic summary. */
        public String describe() {
            return "VkDevice=0x" + Long.toHexString(device)
                    + " VkPhysicalDevice=0x" + Long.toHexString(physicalDevice)
                    + " VkInstance=0x" + Long.toHexString(instance)
                    + (ownedByHost ? tr(" (host-owned)") : tr(" (core-owned)"));
        }
    }

    /**
     * Host frame textures. Every image may be null; the kernel maps them to {@link
     * dev.luxloader.api.frame.FrameTextures} and reports missing inputs.
     * @param color scene color at render resolution
     * @param depth depth at render resolution
     * @param motionVectors optional motion vectors at render resolution
     * @param exposure exposure, usually 1x1
     * @param ui composed UI at display resolution
     * @param swapchain current swapchain image at display resolution
     * @param renderWidth render width
     * @param renderHeight render height
     * @param displayWidth display width
     * @param displayHeight display height
     */
    record HostFrameTextures(
            ImageHandle color,
            ImageHandle depth,
            ImageHandle motionVectors,
            ImageHandle exposure,
            ImageHandle ui,
            ImageHandle swapchain,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            int colorFormat,
            int depthFormat) {

        /** No textures available for this frame. */
        public static final HostFrameTextures EMPTY =
                new HostFrameTextures(null, null, null, null, null, null, 0, 0, 0, 0, 0);

        public HostFrameTextures {
            if (renderWidth < 0 || renderHeight < 0 || displayWidth < 0 || displayHeight < 0) {
                throw new IllegalArgumentException(tr("Resolution must not be negative"));
            }
        }

        /**
         * Convenience constructor with unknown color format (zero). Consumers must fall back when the format
         * is unknown; inventing a view format for a host texture can cause device loss.
         */
        public HostFrameTextures(ImageHandle color, ImageHandle depth, ImageHandle motionVectors,
                                 ImageHandle exposure, ImageHandle ui, ImageHandle swapchain,
                                 int renderWidth, int renderHeight,
                                 int displayWidth, int displayHeight) {
            this(color, depth, motionVectors, exposure, ui, swapchain,
                    renderWidth, renderHeight, displayWidth, displayHeight, 0, 0);
        }

        /** Compatibility constructor for adapters that have not exposed depth format yet. */
        public HostFrameTextures(ImageHandle color, ImageHandle depth, ImageHandle motionVectors,
                                 ImageHandle exposure, ImageHandle ui, ImageHandle swapchain,
                                 int renderWidth, int renderHeight,
                                 int displayWidth, int displayHeight, int colorFormat) {
            this(color, depth, motionVectors, exposure, ui, swapchain,
                    renderWidth, renderHeight, displayWidth, displayHeight, colorFormat, 0);
        }

        /** Whether color and output satisfy spatial upscaling inputs. */
        public boolean canRenderAnything() {
            return color != null && !color.isNull() && swapchain != null && !swapchain.isNull();
        }

        /** Whether color, depth and motion vectors satisfy temporal upscaling inputs. */
        public boolean hasTemporalInputs() {
            return canRenderAnything()
                    && depth != null && !depth.isNull()
                    && motionVectors != null && !motionVectors.isNull();
        }

        /** List missing inputs for diagnostics and fallback selection. */
        public List<String> missingInputs() {
            List<String> missing = new java.util.ArrayList<>();
            if (color == null || color.isNull()) {
                missing.add(tr("Color"));
            }
            if (depth == null || depth.isNull()) {
                missing.add(tr("Depth"));
            }
            if (motionVectors == null || motionVectors.isNull()) {
                missing.add(tr("Motion vectors"));
            }
            if (swapchain == null || swapchain.isNull()) {
                missing.add(tr("Swapchain image"));
            }
            return List.copyOf(missing);
        }

        public boolean hasRenderSize() {
            return renderWidth > 0 && renderHeight > 0;
        }

        public boolean hasDisplaySize() {
            return displayWidth > 0 && displayHeight > 0;
        }

        /** Diagnostic summary. */
        public String describe() {
            return "render=" + renderWidth + "x" + renderHeight
                    + " display=" + displayWidth + "x" + displayHeight
                    + tr(" missing=") + (missingInputs().isEmpty() ? tr("none") : String.join("/", missingInputs()));
        }
    }

    /**
     * Host frame lifecycle hooks: begin (process pending requests), before present (pipeline takeover),
     * and end (reclamation). The host manages other lifecycle details.
     */
    interface HostFrameHook {

        /** No hook; the adapter must drive core manually. */
        HostFrameHook NONE = new HostFrameHook() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String describe() {
                return tr("No frame hook (manual driving required)");
            }
        };

        /** Whether the hook is available. */
        boolean isAvailable();

        /**
         * Registers callbacks once during kernel initialization. The adapter connects them to the host.
         * @param frameBegin before game command recording
         * @param beforePresent after composition, before swapchain presentation
         * @param frameEnd after presentation
         */
        default void install(Runnable frameBegin, Runnable beforePresent, Runnable frameEnd) {
            // Hosts without hooks have no work here.
        }

        /** Diagnostic description. */
        String describe();
    }

    /** Null checks for adapting HostFrameTextures to pipeline-visible textures. */
    static boolean isPresent(ImageHandle handle) {
        return handle != null && !handle.isNull();
    }

    /** Conventional capability keys reference their sole definitions to avoid drift. */
    static java.util.Set<String> knownCapabilityIds() {
        return java.util.Set.copyOf(CapabilityDescriptor.Ids.all());
    }
}
