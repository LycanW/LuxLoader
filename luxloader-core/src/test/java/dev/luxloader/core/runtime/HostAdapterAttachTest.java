package dev.luxloader.core.runtime;

import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuVendor;
import dev.luxloader.api.gpu.ImageHandle;
import dev.luxloader.api.host.HostAdapter;
import dev.luxloader.api.plugin.RenderDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests host adapter integration without a GPU: capability merging, frame texture access, exception
 * isolation and replacement rules. Real device coverage lives in RenderDriverFrameTest.
 */
class HostAdapterAttachTest {

    @TempDir
    Path configDir;

    private RenderDriverImpl driver;

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }

    /** Controllable host adapter stub. */
    private static final class FakeAdapter implements HostAdapter {
        private final String name;
        private final GpuCapabilities capabilities;
        private final HostAdapter.HostFrameTextures textures;
        private final boolean throwOnCapabilities;
        private final boolean throwOnFrameTextures;
        private final List<String> registered = new ArrayList<>();
        private boolean closed;

        FakeAdapter(String name, GpuCapabilities capabilities,
                    HostAdapter.HostFrameTextures textures) {
            this(name, capabilities, textures, false, false);
        }

        FakeAdapter(String name, GpuCapabilities capabilities,
                    HostAdapter.HostFrameTextures textures,
                    boolean throwOnCapabilities, boolean throwOnFrameTextures) {
            this.name = name;
            this.capabilities = capabilities;
            this.textures = textures;
            this.throwOnCapabilities = throwOnCapabilities;
            this.throwOnFrameTextures = throwOnFrameTextures;
        }

        @Override
        public String hostName() {
            return name;
        }

        @Override
        public GpuCapabilities capabilities() {
            if (throwOnCapabilities) {
                throw new IllegalStateException("测试用：能力查询故意失败");
            }
            return capabilities;
        }

        @Override
        public HostDevice device() {
            return null;
        }

        @Override
        public void registerCapabilities(Consumer<CapabilityDescriptor> sink) {
            sink.accept(CapabilityDescriptor.of("test." + name, CapabilityLevel.NATIVE, name));
            registered.add(name);
        }

        @Override
        public HostFrameTextures frameTextures() {
            if (throwOnFrameTextures) {
                throw new IllegalStateException("测试用：取帧纹理故意失败");
            }
            return textures;
        }

        @Override
        public HostFrameHook frameHook() {
            return HostFrameHook.NONE;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private RenderDriverImpl newDriver() {
        RenderDriverImpl impl = new RenderDriverImpl(configDir);
        impl.initialize(RenderDriver.DeviceRequest.attachedToGame());
        return impl;
    }

    private static GpuCapabilities caps(String name) {
        return new GpuCapabilities(name, GpuVendor.INTEL, 0x8086, 0x1234, 0,
                "测试驱动", 0x00403000, Set.of("VK_KHR_ray_query"), Set.of(), 1024, 32768,
                1.0f, 8L * 1024 * 1024 * 1024, 16384);
    }

    @Test
    @DisplayName("Installs And Registers Capabilities")
    void installsAndRegistersCapabilities() {
        driver = newDriver();
        assertNull(driver.hostAdapter(), "No adapter is attached by default");

        FakeAdapter adapter = new FakeAdapter("Minecraft 26.3 / Fabric", caps("Arc A770"),
                HostAdapter.HostFrameTextures.EMPTY);

        assertTrue(driver.attachHostAdapter(adapter), "The first attachment must succeed");
        assertSame(adapter, driver.hostAdapter());
        assertEquals(1, adapter.registered.size(), "Attachment must request capability registration");
        assertTrue(driver.capabilities().atLeast("test.Minecraft 26.3 / Fabric",
                        CapabilityLevel.NATIVE),
                "Adapter capabilities must be available to plugins during onLoad");
    }

    @Test
    @DisplayName("Rejects Second Adapter")
    void rejectsSecondAdapter() {
        driver = newDriver();
        FakeAdapter first = new FakeAdapter("第一个", caps("A"), HostAdapter.HostFrameTextures.EMPTY);
        FakeAdapter second = new FakeAdapter("第二个", caps("B"), HostAdapter.HostFrameTextures.EMPTY);

        assertTrue(driver.attachHostAdapter(first));
        assertFalse(driver.attachHostAdapter(second),
                "Reject replacement of an already attached adapter");
        assertSame(first, driver.hostAdapter());

        // Attaching the same instance twice is idempotent.
        assertTrue(driver.attachHostAdapter(first));
        assertFalse(driver.attachHostAdapter(null), "Reject null");
    }

    @Test
    @DisplayName("Frame Textures Without Adapter")
    void frameTexturesWithoutAdapter() {
        driver = newDriver();

        assertEquals(HostAdapter.HostFrameTextures.EMPTY, driver.hostFrameTextures());
    }

    @Test
    @DisplayName("Uses Adapter Frame Textures")
    void usesAdapterFrameTextures() {
        driver = newDriver();
        HostAdapter.HostFrameTextures textures = new HostAdapter.HostFrameTextures(
                ImageHandle.vkImage(0x1111L, "color"), ImageHandle.vkImage(0x2222L, "depth"),
                null, null, null, ImageHandle.vkImage(0x3333L, "swapchain"),
                1920, 1080, 2560, 1440);
        driver.attachHostAdapter(new FakeAdapter("带纹理", caps("A"), textures));

        HostAdapter.HostFrameTextures fromDriver = driver.hostFrameTextures();

        assertEquals(0x1111L, fromDriver.color().bits());
        assertEquals(2560, fromDriver.displayWidth());
        assertTrue(fromDriver.hasTemporalInputs() == false,
                "Do not advertise temporal upscale inputs without motion vectors");
        assertEquals("运动矢量", fromDriver.missingInputs().get(0));
    }

    @Test
    @DisplayName("Adapter Failures Are Isolated")
    void adapterFailuresAreIsolated() {
        driver = newDriver();
        driver.attachHostAdapter(new FakeAdapter("会抛异常", caps("A"),
                HostAdapter.HostFrameTextures.EMPTY, false, true));

        assertEquals(HostAdapter.HostFrameTextures.EMPTY, driver.hostFrameTextures(),
                "Frame-texture failures must degrade gracefully");
    }

    @Test
    @DisplayName("Null Textures Degrade To Empty")
    void nullTexturesDegradeToEmpty() {
        driver = newDriver();
        driver.attachHostAdapter(new FakeAdapter("返回 null", caps("A"), null));

        assertEquals(HostAdapter.HostFrameTextures.EMPTY, driver.hostFrameTextures());
    }

    @Test
    @DisplayName("Capability Registration Failure Does Not Block Install")
    void capabilityRegistrationFailureDoesNotBlockInstall() {
        driver = newDriver();
        HostAdapter broken = new HostAdapter() {
            @Override
            public String hostName() {
                return "登记会炸的适配器";
            }

            @Override
            public GpuCapabilities capabilities() {
                return caps("A");
            }

            @Override
            public HostDevice device() {
                return null;
            }

            @Override
            public void registerCapabilities(Consumer<CapabilityDescriptor> sink) {
                throw new IllegalStateException("测试用：登记能力故意失败");
            }

            @Override
            public HostFrameTextures frameTextures() {
                return HostFrameTextures.EMPTY;
            }

            @Override
            public HostFrameHook frameHook() {
                return HostFrameHook.NONE;
            }
        };

        assertTrue(driver.attachHostAdapter(broken),
                "Capability-registration failure must not prevent adapter attachment");
        assertSame(broken, driver.hostAdapter());
    }

    @Test
    @DisplayName("Capability Query Failure Does Not Block Install")
    void capabilityQueryFailureDoesNotBlockInstall() {
        driver = newDriver();
        FakeAdapter adapter = new FakeAdapter("能力查询会炸", null,
                HostAdapter.HostFrameTextures.EMPTY, true, false);

        assertTrue(driver.attachHostAdapter(adapter));
        assertSame(adapter, driver.hostAdapter());
    }

    @Test
    @DisplayName("Adapter Shows Up In Diagnostics")
    void adapterShowsUpInDiagnostics() {
        driver = newDriver();
        driver.attachHostAdapter(new FakeAdapter("Minecraft 26.3 / NeoForge", caps("Arc A770"),
                HostAdapter.HostFrameTextures.EMPTY));

        String report = driver.diagnostics().exportReport();

        assertFalse(report.isBlank());
        assertTrue(report.contains("NeoForge") || report.contains("Minecraft"),
                "Diagnostics must identify the host: " + report.substring(0, Math.min(400, report.length())));
    }
}
