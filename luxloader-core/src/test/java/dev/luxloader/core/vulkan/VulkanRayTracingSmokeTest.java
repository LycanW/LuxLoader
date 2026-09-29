package dev.luxloader.core.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

import dev.luxloader.api.gpu.AccelGeometry;
import dev.luxloader.api.gpu.AccelInstance;
import dev.luxloader.api.gpu.AccelStructDesc;
import dev.luxloader.api.gpu.AcceleratorHandle;
import dev.luxloader.api.gpu.BufferDesc;
import dev.luxloader.api.gpu.GpuCapabilities;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Real-device BLAS and TLAS tests covering build sizes, device-address allocation, buffer usage and
 * recorded build commands. Skip explicitly when Vulkan or RT support is unavailable.
 */
class VulkanRayTracingSmokeTest {

    /** One triangle with three float3 vertices. */
    private static final float[] TRIANGLE_VERTICES = {
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f};

    private static final short[] TRIANGLE_INDICES = {0, 1, 2};

    private static VulkanDevice createDeviceWithDiagnostics() {
        var diagnostics = new dev.luxloader.core.diag.DiagnosticsImpl(
                java.nio.file.Path.of("build", "luxloader-diagnostics"), "rt-smoke", true);
        String wanted = System.getProperty("luxloader.test.gpuDevice", "").trim();
        VulkanDevice device = wanted.isEmpty()
                ? VulkanDevice.create("LuxLoader RT Test", true, diagnostics)
                : VulkanDevice.create("LuxLoader RT Test", true, diagnostics, wanted);
        if (device == null) {
            diagnostics.logLines().forEach(System.out::println);
        } else {
            System.out.println("[RT test] Actual device: " + device.capabilities().deviceName());
        }
        return device;
    }

    private static boolean rayTracingUsable(GpuCapabilities caps) {
        return caps.supportsAll("VK_KHR_acceleration_structure",
                        "VK_KHR_deferred_host_operations",
                        "VK_KHR_buffer_device_address")
                && caps.supportsAny("VK_KHR_ray_query", "VK_KHR_ray_tracing_pipeline");
    }

    private static ByteBuffer floats(float... values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder());
        for (float v : values) {
            buffer.putFloat(v);
        }
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer shorts(short... values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 2)
                .order(ByteOrder.nativeOrder());
        for (short v : values) {
            buffer.putShort(v);
        }
        buffer.flip();
        return buffer;
    }

    @Test
    @DisplayName("Builds Bottom And Top Level Structures")
    void buildsBottomAndTopLevelStructures() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");

        try {
            GpuCapabilities caps = device.capabilities();
            System.out.println("[RT test] " + caps.deviceName() + "  Vulkan " + caps.apiVersionString()
                    + "  acceleration_structure=" + caps.supports("VK_KHR_acceleration_structure")
                    + " ray_query=" + caps.supports("VK_KHR_ray_query")
                    + " rt_pipeline=" + caps.supports("VK_KHR_ray_tracing_pipeline"));
            Assumptions.assumeTrue(rayTracingUsable(caps),
                    "Device lacks acceleration structures, device addresses or a ray-query/RT-pipeline path");

            var provider = new VulkanResourceProvider(device, "rt-smoke", 0L);
            try {
                // Geometry: one triangle.
                var vertices = provider.buffer(BufferDesc.hostVisible("rt-tri-vertices",
                        TRIANGLE_VERTICES.length * 4L, BufferDesc.Usage.ACCEL_STRUCT_BUILD_INPUT));
                var indices = provider.buffer(BufferDesc.hostVisible("rt-tri-indices",
                        TRIANGLE_INDICES.length * 2L, BufferDesc.Usage.ACCEL_STRUCT_BUILD_INPUT));
                provider.upload(vertices, 0L, floats(TRIANGLE_VERTICES));
                provider.upload(indices, 0L, shorts(TRIANGLE_INDICES));

                // Geometry buffers remain allocated, so memory accounting must be checked relative to the baseline.
                long baseline = provider.usedVramBytes();

                // ---- BLAS ----
                AccelStructDesc blasDesc = AccelStructDesc.staticBlas("rt-smoke-blas", 1, 3,
                        new AccelStructDesc.BlasLayout(12, AccelGeometry.IndexType.UINT16, true));
                AcceleratorHandle blas = provider.accelerator(blasDesc);
                assertTrue(blas.supported(), "BLAS creation must succeed on a supported device");
                assertTrue(blas.hasDeviceAddress(), "BLAS must have a device address for shader access");
                assertTrue(blas.size() > 0L, "BLAS storage size must be positive");

                AccelGeometry geometry = AccelGeometry.indexed(vertices, 3, 12, indices, 3,
                        AccelGeometry.IndexType.UINT16);
                assertEquals(1, geometry.triangleCount());
                provider.buildAccelerator(blas, geometry);
                System.out.println("[RT test] BLAS built: " + blas.size() + " bytes; device address 0x"
                        + Long.toHexString(blas.deviceAddress()));

                // ---- TLAS ----
                AccelStructDesc tlasDesc = AccelStructDesc.tlas("rt-smoke-tlas", 1);
                AcceleratorHandle tlas = provider.accelerator(tlasDesc);
                assertTrue(tlas.supported(), "TLAS creation must succeed");
                assertTrue(tlas.hasDeviceAddress(), "TLAS must have a device address");

                AccelInstance instance = AccelInstance.of(blas,
                        AccelInstance.translation(0f, 0f, -2f), 7);
                assertEquals(12, instance.transform().length,
                        "Preserve all 12 transform floats in the defensive copy");
                provider.buildTopLevel(tlas, List.of(instance));
                System.out.println("[RT test] TLAS built: " + tlas.size() + " bytes; device address 0x"
                        + Long.toHexString(tlas.deviceAddress()));

                // An empty instance list is valid, but must not create a zero-sized backing buffer.
                provider.buildTopLevel(tlas, List.of());

                // Release resources and verify accounting returns exactly to baseline.
                long withStructures = provider.usedVramBytes();
                assertTrue(withStructures > baseline,
                        "Two acceleration structures must increase allocation (baseline=" + baseline
                                + " withStructures=" + withStructures + "）");
                provider.release(blas);
                provider.release(tlas);
                assertEquals(baseline, provider.usedVramBytes(),
                        "Releasing both structures must restore baseline memory accounting"
                                + " to prevent false budget failures after repeated switching (baseline=" + baseline + "）");
            } finally {
                provider.closeAll();
            }
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("Returns Unsupported Instead Of Throwing")
    void returnsUnsupportedInsteadOfThrowing() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());
        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");
        try {
            var provider = new VulkanResourceProvider(device, "rt-smoke-negative", 0L);
            try {
                // A zero-count BLAS description is invalid input and must fail in its constructor.
                org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                        () -> AccelStructDesc.staticBlas("bad", -1, 0));

                // Return success when supported and unsupported otherwise; neither case should throw.
                AcceleratorHandle handle = provider.accelerator(
                        AccelStructDesc.staticBlas("rt-smoke-probe", 1, 3));
                assertNotNull(handle);
                assertEquals(rayTracingUsable(device.capabilities()), handle.supported(),
                        "supported() must agree with device capabilities");
            } finally {
                provider.closeAll();
            }
        } finally {
            device.close();
        }
    }
}
