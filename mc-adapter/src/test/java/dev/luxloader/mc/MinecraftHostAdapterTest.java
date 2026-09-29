package dev.luxloader.mc;

import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.gpu.GpuVendor;
import dev.luxloader.api.host.HostAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests capability derivation, frame texture assembly and graceful fallback using stubs. Mapping
 * overrides point to test classes, exercising the override mechanism without spoofing Minecraft
 * package names.
 */
class MinecraftHostAdapterTest {

    // Stubs matching the 26.3 object shapes.

    /** Native-handle stub exposing address(), as LWJGL wrappers do. */
    public static final class FakeHandle {
        private final long address;

        FakeHandle(long address) {
            this.address = address;
        }

        public long address() {
            return address;
        }

        public FakeHandle getPhysicalDevice() { return new FakeHandle(0x4567L); }
        public FakeInstanceCapabilities getCapabilitiesInstance() { return new FakeInstanceCapabilities(); }
    }

    public static final class FakeInstanceCapabilities {
        public final int apiVersion = (1 << 22) | (3 << 12);
    }

    /** FrontendGpuSurface stub with a private backend field. */
    public static final class FakeSurface {
        private final Object backend;

        FakeSurface(Object backend) {
            this.backend = backend;
        }
    }

    /** VulkanGpuSurface stub with a private device field. */
    public static final class FakeBackend {
        private final Object device;

        FakeBackend(Object device) {
            this.device = device;
        }
    }

    /** VulkanInstance stub. */
    public static final class FakeInstance {
        private final long handle;

        FakeInstance(long handle) {
            this.handle = handle;
        }

        public long vkInstance() {
            return handle;
        }

        public Set<String> getEnabledExtensions() { return Set.of("VK_KHR_surface"); }
    }

    /** VulkanDevice stub exposing vkDevice(), instance() and getDeviceInfo(). */
    public static final class FakeDevice {
        private final long deviceHandle;
        private final FakeInstance instance;
        private final Object deviceInfo;

        FakeDevice(long deviceHandle, FakeInstance instance, Object deviceInfo) {
            this.deviceHandle = deviceHandle;
            this.instance = instance;
            this.deviceInfo = deviceInfo;
        }

        public FakeHandle vkDevice() {
            return new FakeHandle(deviceHandle);
        }

        public FakeInstance instance() {
            return instance;
        }

        public Object getDeviceInfo() {
            return deviceInfo;
        }
    }

    /** DeviceLimits stub. */
    public static final class FakeLimits {
        public int maxTextureSize() {
            return 16384;
        }
    }

    /** DeviceInfo stub with matching component names. */
    public static final class FakeDeviceInfo {
        private final String name;
        private final String vendorName;
        private final String driverInfo;
        private final float timestampPeriod;
        private final Set<String> extensions;
        private final FakeLimits limits = new FakeLimits();

        FakeDeviceInfo(String name, String vendorName, String driverInfo, float timestampPeriod,
                       Set<String> extensions) {
            this.name = name;
            this.vendorName = vendorName;
            this.driverInfo = driverInfo;
            this.timestampPeriod = timestampPeriod;
            this.extensions = extensions;
        }

        public String name() {
            return name;
        }

        public String vendorName() {
            return vendorName;
        }

        public String driverInfo() {
            return driverInfo;
        }

        public float timestampPeriod() {
            return timestampPeriod;
        }

        public Set<String> underlyingExtensions() {
            return extensions;
        }

        public FakeLimits limits() {
            return limits;
        }

        public String backendName() {
            return "Vulkan";
        }
    }

    /** Texture stub. */
    public static final class FakeTexture {
        private final long image;

        FakeTexture(long image) {
            this.image = image;
        }

        public long vkImage() {
            return image;
        }
    }

    /** Texture view stub. */
    public static final class FakeView {
        private static final AtomicInteger CLOSED = new AtomicInteger();
        private final FakeTexture texture;
        private final int width;
        private final int height;

        FakeView(FakeTexture texture, int width, int height) {
            this.texture = texture;
            this.width = width;
            this.height = height;
        }

        public FakeTexture texture() {
            return texture;
        }

        public int getWidth(int mip) {
            return width;
        }

        public int getHeight(int mip) {
            return height;
        }
    }

    /** Override all five mapping hops with test classes. */
    private static Map<String, String> fakeOverrides() {
        return Map.of(
                MinecraftMapping.Hookpoint.SURFACE_BACKEND.name(),
                FakeSurface.class.getName() + "#backend",
                MinecraftMapping.Hookpoint.SURFACE_BACKEND_DEVICE.name(),
                FakeBackend.class.getName() + "#device",
                MinecraftMapping.Hookpoint.DEVICE_HANDLE.name(),
                FakeDevice.class.getName() + "#vkDevice",
                MinecraftMapping.Hookpoint.INSTANCE_HANDLE.name(),
                FakeInstance.class.getName() + "#vkInstance",
                MinecraftMapping.Hookpoint.TEXTURE_VIEW_TEXTURE.name(),
                FakeView.class.getName() + "#texture",
                MinecraftMapping.Hookpoint.TEXTURE_IMAGE_HANDLE.name(),
                FakeTexture.class.getName() + "#vkImage");
    }

    private static final long DEVICE_HANDLE = 0xDEADBEEFL;
    private static final long INSTANCE_HANDLE = 0xCAFEBABEL;
    private static final long IMAGE_HANDLE = 0x1234ABCDL;

    private static MinecraftHostAdapter newAdapter(FakeDeviceInfo info) {
        FakeDevice device = new FakeDevice(DEVICE_HANDLE, new FakeInstance(INSTANCE_HANDLE), info);
        FakeSurface surface = new FakeSurface(new FakeBackend(device));
        MinecraftHostAdapter adapter = MinecraftHostAdapter.create(surface,
                MinecraftHostAdapterTest.class.getClassLoader(), "Fabric", "26.3",
                fakeOverrides(), false);
        assertNotNull(adapter, "Complete stubs must construct a working adapter");
        return adapter;
    }

    private static FakeDeviceInfo epycInfo() {
        return new FakeDeviceInfo("Intel(R) Arc(TM) A770 Graphics", "Intel", "32.0.101.8974",
                1.0f, Set.of("VK_KHR_acceleration_structure", "VK_KHR_ray_query",
                "VK_EXT_descriptor_buffer"));
    }

    // Device access chain.

    @Test
    @DisplayName("Resolves Device Chain")
    void resolvesDeviceChain() {
        MinecraftHostAdapter adapter = newAdapter(epycInfo());

        assertNotNull(adapter.device(), "Expose the host device");
        HostAdapter.HostDevice device = adapter.device();
        assertEquals(DEVICE_HANDLE, device.device());
        assertEquals(INSTANCE_HANDLE, device.instance());
        assertEquals(0x4567L, device.physicalDevice(), "Read the VkDevice wrapper's actual parent");
        assertTrue(device.hasPhysicalDevice());
        assertEquals((1 << 22) | (3 << 12), device.instanceApiVersion());
        assertEquals(Set.of("VK_KHR_surface"), device.instanceExtensions());
        assertTrue(device.isUsable());
        assertTrue(device.ownedByHost(), "The loader must never destroy the game's device");
    }

    @Test
    @DisplayName("Reports Names")
    void reportsNames() {
        MinecraftHostAdapter adapter = newAdapter(epycInfo());

        assertEquals("Minecraft 26.3 / Fabric", adapter.hostName());
        assertEquals("Vulkan", adapter.backendName());
        assertTrue(adapter.describe().contains("捕获帧=0"), adapter.describe());
    }

    @Test
    @DisplayName("Derives Capabilities From Device Info")
    void derivesCapabilitiesFromDeviceInfo() {
        MinecraftHostAdapter adapter = newAdapter(epycInfo());

        var caps = adapter.capabilities();
        assertNotNull(caps, "Available DeviceInfo must produce a capability snapshot");
        assertEquals("Intel(R) Arc(TM) A770 Graphics", caps.deviceName());
        assertEquals(GpuVendor.INTEL, caps.vendor());
        assertEquals("32.0.101.8974", caps.driverName());
        assertEquals(3, caps.extensions().size());
        assertTrue(caps.supports("VK_KHR_ray_query"));
        assertTrue(caps.supportsRayTracing(), "Advertised RT extensions must enable the corresponding capability");
        assertTrue(caps.supportsDescriptorBuffer());
        assertTrue(caps.supportsTimestamps(), "Nonzero timestampPeriod indicates timing support");
        assertEquals(16384, caps.maxImageDimension2D());

        // Unavailable values remain zero or empty rather than receiving plausible guesses.
        assertEquals(0, caps.apiVersion(),
                "DeviceInfo does not provide the Vulkan version; leave it unknown"
                        + " until physical-device enumeration supplies it");
        assertEquals(0L, caps.deviceMemoryBytes(), "DeviceInfo does not provide VRAM capacity");
    }

    @Test
    @DisplayName("Maps Vendor Names")
    void mapsVendorNames() {
        assertEquals(GpuVendor.INTEL, MinecraftHostAdapter.vendorOf("Intel"));
        assertEquals(GpuVendor.NVIDIA, MinecraftHostAdapter.vendorOf("NVIDIA Corporation"));
        assertEquals(GpuVendor.AMD, MinecraftHostAdapter.vendorOf("Advanced Micro Devices, Inc."));
        assertEquals(GpuVendor.AMD, MinecraftHostAdapter.vendorOf("ATI Technologies Inc."));
        assertEquals(GpuVendor.QUALCOMM, MinecraftHostAdapter.vendorOf("Qualcomm Technologies, Inc."));
        assertEquals(GpuVendor.IMAGINATION, MinecraftHostAdapter.vendorOf("Imagination Technologies"));

        // Hardware vendor takes precedence over driver project: Mesa on Intel still represents an Intel GPU.
        assertEquals(GpuVendor.INTEL, MinecraftHostAdapter.vendorOf("Intel open-source Mesa driver"));
        assertEquals(GpuVendor.MESA, MinecraftHostAdapter.vendorOf("Mesa/X.org"));

        assertEquals(GpuVendor.UNKNOWN, MinecraftHostAdapter.vendorOf("Some Random Vendor"));
        assertEquals(GpuVendor.UNKNOWN, MinecraftHostAdapter.vendorOf(""));
        assertEquals(GpuVendor.UNKNOWN, MinecraftHostAdapter.vendorOf(null));
    }

    @Test
    @DisplayName("Survives Missing Device Info")
    void survivesMissingDeviceInfo() {
        MinecraftHostAdapter adapter = newAdapter(null);

        assertNotNull(adapter.device(), "The device access chain must remain usable");
        var caps = adapter.capabilities();
        assertNotNull(caps, "Return a fallback capability snapshot without DeviceInfo");
        assertEquals(GpuVendor.UNKNOWN, caps.vendor());
        assertTrue(caps.extensions().isEmpty());
        assertFalse(caps.supportsRayTracing(), "Unknown support must default to unavailable");
    }

    // Frame textures.

    @Test
    @DisplayName("Captures Frame Textures")
    void capturesFrameTextures() {
        MinecraftHostAdapter adapter = newAdapter(epycInfo());
        FakeView view = new FakeView(new FakeTexture(IMAGE_HANDLE), 2560, 1440);

        // Usage 15 combines COPY_DST, COPY_SRC, TEXTURE_BINDING and RENDER_ATTACHMENT, matching MainTarget.allocateColorAttachment. Bit 4 enables sampling.
        assertTrue(adapter.captureFrame(view, 37, 15), "Capture must succeed with valid view and image handles");
        assertEquals(1, adapter.capturedFrameCount());
        assertEquals(0, adapter.droppedFrameCount());

        HostAdapter.HostFrameTextures textures = adapter.frameTextures();
        // The hook exposes the main render target's composited color view, not a swapchain image. Swapchain images are raw handles owned by VulkanGpuSurface. Returning null here previously forced plugins into their clear-only fallback despite valid sampleable scene color.
        assertEquals(IMAGE_HANDLE, textures.color().bits(),
                "Expose sampleable scene color supplied by the host");
        assertEquals(IMAGE_HANDLE, textures.swapchain().bits(),
                "Shared handles reflect variable reuse and do not identify a swapchain image");
        assertEquals(2560, textures.displayWidth());
        assertEquals(1440, textures.displayHeight());
        assertTrue(textures.canRenderAnything(),
                "Color and output satisfy minimum spatial-effect inputs");
        assertTrue(textures.hasRenderSize(),
                "Main-render-target dimensions define the render resolution");

        // Missing resources remain empty so the pipeline can choose a fallback.
        assertNull(textures.depth());
        assertNull(textures.motionVectors());
        assertNull(textures.exposure());
        assertTrue(textures.missingInputs().contains("运动矢量"), textures.describe());
        assertFalse(textures.hasTemporalInputs(), "Temporal upscaling requires depth and motion vectors");
    }

    @Test
    @DisplayName("Captures Depth Only When Sampleable")
    void capturesDepthOnlyWhenSampleable() {
        MinecraftHostAdapter adapter = newAdapter(epycInfo());
        FakeView color = new FakeView(new FakeTexture(IMAGE_HANDLE), 640, 360);
        FakeView depth = new FakeView(new FakeTexture(IMAGE_HANDLE + 1), 640, 360);

        assertTrue(adapter.captureFrame(color, 37, 15, depth, 126, 15));
        assertEquals(IMAGE_HANDLE + 1, adapter.frameTextures().depth().bits());
        assertEquals(126, adapter.frameTextures().depthFormat());

        assertTrue(adapter.captureFrame(color, 37, 15, depth, 126, 2));
        assertNull(adapter.frameTextures().depth());
        assertEquals(0, adapter.frameTextures().depthFormat());
    }

    @Test
    @DisplayName("Refuses To Hand Over Unsampleable Frame")
    void refusesToHandOverUnsampleableFrame() {
        MinecraftHostAdapter adapter = newAdapter(epycInfo());
        FakeView view = new FakeView(new FakeTexture(IMAGE_HANDLE), 2560, 1440);

        // Usage 2 provides COPY_SRC without TEXTURE_BINDING (4). Such images can be presented but must not be exposed for sampling.
        assertTrue(adapter.captureFrame(view, 37, 2));
        HostAdapter.HostFrameTextures textures = adapter.frameTextures();

        assertNull(textures.color(), "Do not expose non-sampleable images as scene color");
        assertFalse(textures.canRenderAnything(), "Let plugins fall back instead of sampling an invalid view");
    }

    @Test
    @DisplayName("Clear Frame Drops Stale Handle")
    void clearFrameDropsStaleHandle() {
        MinecraftHostAdapter adapter = newAdapter(epycInfo());
        adapter.captureFrame(new FakeView(new FakeTexture(IMAGE_HANDLE), 1920, 1080));
        assertFalse(adapter.frameTextures().canRenderAnything(),
                "Successful capture does not imply sampleable scene color is available");

        adapter.clearFrame();

        assertEquals(HostAdapter.HostFrameTextures.EMPTY, adapter.frameTextures());
        assertNull(adapter.capturedFrame());
    }

    @Test
    @DisplayName("Records Dropped Frames")
    void recordsDroppedFrames() {
        MinecraftHostAdapter adapter = newAdapter(epycInfo());

        assertFalse(adapter.captureFrame(null), "An empty view must fail capture");
        // A zero texture handle cannot resolve to a VkImage.
        assertFalse(adapter.captureFrame(new FakeView(new FakeTexture(0L), 1920, 1080)));
        // Zero dimensions are unavailable rather than a usable guessed resolution.
        assertFalse(adapter.captureFrame(new FakeView(new FakeTexture(IMAGE_HANDLE), 0, 0)));

        assertEquals(0, adapter.capturedFrameCount());
        assertEquals(3, adapter.droppedFrameCount(),
                "Count every capture failure for diagnostics");
        assertEquals(HostAdapter.HostFrameTextures.EMPTY, adapter.frameTextures());
    }

    // Capability registration.

    @Test
    @DisplayName("Registers Capabilities Honestly")
    void registersCapabilitiesHonestly() {
        MinecraftHostAdapter adapter = newAdapter(epycInfo());
        List<CapabilityDescriptor> registered = new ArrayList<>();
        adapter.registerCapabilities(registered::add);

        assertUsable(registered, CapabilityDescriptor.Ids.VULKAN_BACKEND, CapabilityLevel.NATIVE);
        assertUsable(registered, CapabilityDescriptor.Ids.DEVICE_ACCESSIBLE, CapabilityLevel.NATIVE);
        assertUsable(registered, CapabilityDescriptor.Ids.DEVICE_INFO, CapabilityLevel.PARTIAL);

        // Without the frame hook, report unavailable so pipelines do not expect frame callbacks.
        assertUnusable(registered, CapabilityDescriptor.Ids.FRAME_HOOK);
        assertUnusable(registered, CapabilityDescriptor.Ids.RESIZE_SIGNAL);

        adapter.markHooksInstalled(true);
        List<CapabilityDescriptor> afterHooks = new ArrayList<>();
        adapter.registerCapabilities(afterHooks::add);
        assertUsable(afterHooks, CapabilityDescriptor.Ids.FRAME_HOOK, CapabilityLevel.NATIVE);
        assertUsable(afterHooks, CapabilityDescriptor.Ids.RESIZE_SIGNAL, CapabilityLevel.PARTIAL);

        // Presentation interception is PARTIAL because external images require a copy into a host-created texture. Record this extra full-screen blit in the capability detail.
        assertUsable(afterHooks, CapabilityDescriptor.Ids.PRESENT_INTERCEPT, CapabilityLevel.PARTIAL);
        CapabilityDescriptor intercept =
                find(afterHooks, CapabilityDescriptor.Ids.PRESENT_INTERCEPT);
        assertTrue(intercept.provider().contains("blit"),
                "Describe the additional copy cost: " + intercept.provider());

        // Explicit unsupported capabilities let pipelines select a fallback reliably.
        assertUnusable(afterHooks, CapabilityDescriptor.Ids.MOTION_VECTORS_AVAILABLE);
        assertUnusable(afterHooks, CapabilityDescriptor.Ids.SCENE_EXTRACTION);

        for (CapabilityDescriptor descriptor : registered) {
            assertFalse(descriptor.provider().isBlank(),
                    "Every capability must identify its provider: " + descriptor);
        }
    }

    @Test
    @DisplayName("Registers Unavailable When Device Missing")
    void registersUnavailableWhenDeviceMissing() {
        MinecraftHostAdapter adapter = MinecraftHostAdapter.create(new Object(),
                getClass().getClassLoader(), "NeoForge", "26.3", Map.of(), false);
        assertNotNull(adapter);

        List<CapabilityDescriptor> registered = new ArrayList<>();
        adapter.registerCapabilities(registered::add);

        assertUnusable(registered, CapabilityDescriptor.Ids.VULKAN_BACKEND);
        assertUnusable(registered, CapabilityDescriptor.Ids.DEVICE_ACCESSIBLE);

        CapabilityDescriptor backend = find(registered, CapabilityDescriptor.Ids.VULKAN_BACKEND);
        assertTrue(backend.detail().contains("OpenGL") || backend.detail().contains("映射"),
                "Explain the actual backend or mapping failure: "
                        + backend.detail());
        assertTrue(adapter.describe().contains("原因"), adapter.describe());
    }

    @Test
    @DisplayName("Does Not Install Hooks Itself")
    void doesNotInstallHooksItself() {
        MinecraftHostAdapter adapter = newAdapter(epycInfo());

        assertEquals(HostAdapter.HostFrameHook.NONE, adapter.frameHook());
        assertFalse(adapter.frameHook().isAvailable());
        assertFalse(adapter.hasReliableResizeSignal(),
                "Without a configure signal, report per-frame dimension checks as the fallback");
        assertFalse(adapter.supportsSceneExtraction(),
                "Report unsupported scene extraction accurately");
    }

    @Test
    @DisplayName("Reuses Resolved Chain")
    void reusesResolvedChain() {
        MinecraftHostAdapter adapter = newAdapter(epycInfo());
        MinecraftGraphicsAccess.DeviceChain chain = adapter.deviceChain();

        for (int i = 0; i < 100; i++) {
            assertSame(chain, adapter.deviceChain());
        }
        // Capturing 100 frames must not repeat mapping resolution; check counting and idempotence.
        for (int i = 0; i < 100; i++) {
            assertTrue(adapter.captureFrame(new FakeView(new FakeTexture(IMAGE_HANDLE), 1920, 1080)));
        }
        assertEquals(100, adapter.capturedFrameCount());
        assertTrue(adapter.describe().contains("捕获帧=100"), adapter.describe());
    }

    // Helpers.

    private static CapabilityDescriptor find(List<CapabilityDescriptor> list, String id) {
        return list.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Unregistered capability: " + id + "; actual: " + list));
    }

    private static void assertUsable(List<CapabilityDescriptor> list, String id,
                                     CapabilityLevel level) {
        CapabilityDescriptor descriptor = find(list, id);
        assertEquals(level, descriptor.level(), id + " has an unexpected level: " + descriptor.detail());
        assertTrue(descriptor.isUsable(), id + " must be available: " + descriptor.detail());
    }

    private static void assertUnusable(List<CapabilityDescriptor> list, String id) {
        CapabilityDescriptor descriptor = find(list, id);
        assertFalse(descriptor.isUsable(),
                id + " must be unavailable: " + descriptor.level() + " — " + descriptor.detail());
        assertFalse(descriptor.detail().isBlank(), id + " must explain why it is unavailable");
    }
}
