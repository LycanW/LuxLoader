package dev.luxloader.core.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.luxloader.api.gpu.AccelGeometry;
import dev.luxloader.api.gpu.AccelInstance;
import dev.luxloader.api.gpu.AccelStructDesc;
import dev.luxloader.api.gpu.AcceleratorHandle;
import dev.luxloader.api.gpu.BufferDesc;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.shader.ShaderCompileResult;
import dev.luxloader.shader.ShaderSource;
import dev.luxloader.shader.SlangCompiler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies an actual ray-query hit, covering acceleration-structure descriptors, shader targets and
 * TLAS transforms. The triangle lies at z=0 with vertices (0,0,0), (1,0,0), (0,1,0). An
 * identity-transformed ray from (0.25,0.25,-1) along +z must hit primitive 0 at t=1.
 */
class VulkanRayQueryTest {

    /** Minimal inline RayQuery shader; a separate RT pipeline and SBT are unnecessary for this intersection test. */
    private static final String SHADER = """
            [[vk::binding(0, 0)]] RaytracingAccelerationStructure gScene;
            [[vk::binding(1, 0)]] RWStructuredBuffer<float> gResult;

            [shader("compute")]
            [numthreads(1, 1, 1)]
            void main(uint3 tid : SV_DispatchThreadID)
            {
                RayDesc ray;
                ray.Origin = float3(0.25, 0.25, -1.0);
                ray.Direction = float3(0.0, 0.0, 1.0);
                ray.TMin = 0.0;
                ray.TMax = 1000.0;

                RayQuery<RAY_FLAG_NONE> q;
                q.TraceRayInline(gScene, RAY_FLAG_NONE, 0xFF, ray);
                q.Proceed();

                bool hit = q.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
                // Use -1 for a miss to distinguish absent hits from incorrect hit distances.
                gResult[0] = hit ? q.CommittedRayT() : -1.0;
                gResult[1] = hit ? float(q.CommittedPrimitiveIndex()) : -2.0;
            }
            """;

    private static ByteBuffer floats(float... values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        for (float v : values) {
            buffer.putFloat(v);
        }
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer shorts(short... values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 2).order(ByteOrder.nativeOrder());
        for (short v : values) {
            buffer.putShort(v);
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Locate slangc from the explicit property, then parent directories containing runtime caches or
     * bundled binaries.
     */
    private static Optional<Path> findSlangc() {
        String explicit = System.getProperty("luxloader.test.slangc", "").trim();
        if (!explicit.isEmpty()) {
            Path p = Path.of(explicit);
            return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
        }
        // Gradle tests run from the module directory, not the repository root; search upward rather than assuming a root-relative path.
        Path root = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && root != null; up++, root = root.getParent()) {
            Path cached = root.resolve(".tools").resolve("slang-cache");
            if (Files.isDirectory(cached)) {
                Optional<Path> found = searchSlangc(cached);
                if (found.isPresent()) {
                    return found;
                }
            }
            Path bundled = root.resolve("luxloader-shader").resolve("src").resolve("main")
                    .resolve("resources").resolve("native").resolve("windows-x86_64")
                    .resolve("slangc.exe");
            if (Files.isRegularFile(bundled)) {
                return Optional.of(bundled);
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> searchSlangc(Path cacheRoot) {
        try (Stream<Path> s = Files.walk(cacheRoot, 5)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("slangc.exe"))
                    .max(Comparator.comparing(Path::toString));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean rayTracingUsable(GpuCapabilities caps) {
        return caps.supportsAll("VK_KHR_acceleration_structure",
                        "VK_KHR_deferred_host_operations",
                        "VK_KHR_buffer_device_address")
                && caps.supports("VK_KHR_ray_query");
    }

    @Test
    @DisplayName("Ray Query Hits The Triangle")
    void rayQueryHitsTheTriangle() throws Exception {
        assumeTrue(VulkanApi.isAvailable(), "Vulkan unavailable: " + VulkanApi.unavailableReason());

        Optional<Path> slangc = findSlangc();
        assumeTrue(slangc.isPresent(),
                "No slangc found; set -Dluxloader.test.slangc=<path> to enable this test");

        Path work = Files.createTempDirectory("luxloader-rayquery");
        Optional<SlangCompiler> compiler = SlangCompiler.detect(slangc.get(), work);
        assumeTrue(compiler.isPresent(), "Found slangc but initialization failed: " + slangc.get());

        // SlangCompiler exposes close() without implementing AutoCloseable, so use try/finally.
        SlangCompiler slang = compiler.get();
        try {
            ShaderCompileResult compiled = slang.compile(
                    ShaderSource.FromSource.of("ray-query-hit-test",
                            dev.luxloader.shader.ShaderStage.COMPUTE, SHADER),
                    List.of());
            assertTrue(compiled.success(), "Slang compilation failed: " + compiled.report());
            byte[] spirv = compiled.spirv();
            assertTrue(spirv.length > 0, "Successful compilation returned empty SPIR-V");
            System.out.println("[Ray query] Slang compiled successfully; SPIR-V size " + spirv.length + " bytes");

            runAgainstGpu(spirv);
        } finally {
            slang.close();
        }
    }

    private void runAgainstGpu(byte[] spirv) {
        var diagnostics = new dev.luxloader.core.diag.DiagnosticsImpl(
                Path.of("build", "luxloader-diagnostics"), "ray-query", true);
        VulkanDevice device = VulkanDevice.create("LuxLoader RayQuery Test", true, diagnostics);
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");
        try {
            GpuCapabilities caps = device.capabilities();
            Assumptions.assumeTrue(rayTracingUsable(caps),
                    "Device or driver lacks ray-query requirements (" + caps.deviceName() + "）");
            System.out.println("[Ray query] Device=" + caps.deviceName()
                    + " ray_query=" + caps.supports("VK_KHR_ray_query"));

            var provider = new VulkanResourceProvider(device, "ray-query", 0L);
            try {
                // Geometry: a right triangle in the z=0 plane.
                float[] vertices = {0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f};
                short[] indices = {0, 1, 2};
                var vertexBuffer = provider.buffer(BufferDesc.hostVisible("rq-vertices",
                        vertices.length * 4L, BufferDesc.Usage.ACCEL_STRUCT_BUILD_INPUT));
                var indexBuffer = provider.buffer(BufferDesc.hostVisible("rq-indices",
                        indices.length * 2L, BufferDesc.Usage.ACCEL_STRUCT_BUILD_INPUT));
                provider.upload(vertexBuffer, 0L, floats(vertices));
                provider.upload(indexBuffer, 0L, shorts(indices));

                AcceleratorHandle blas = provider.accelerator(
                        AccelStructDesc.staticBlas("rq-blas", 1, 3,
                                new AccelStructDesc.BlasLayout(12,
                                        AccelGeometry.IndexType.UINT16, true)));
                assertTrue(blas.supported());
                provider.buildAccelerator(blas, AccelGeometry.indexed(
                        vertexBuffer, 3, 12, indexBuffer, 3, AccelGeometry.IndexType.UINT16));

                AcceleratorHandle tlas = provider.accelerator(AccelStructDesc.tlas("rq-tlas", 1));
                assertTrue(tlas.supported());
                // Identity transform preserves the analytic hit distance t=1.
                provider.buildTopLevel(tlas, List.of(
                        AccelInstance.of(blas, AccelInstance.identityTransform(), 0)));

                // Result buffer containing two floats.
                var result = provider.buffer(BufferDesc.hostVisible("rq-result", 2 * 4L,
                        BufferDesc.Usage.STORAGE, BufferDesc.Usage.TRANSFER_SRC));
                assertTrue(result.bits() != 0L);

                // Pipeline bindings: acceleration structure at 0 and result buffer at 1.
                var pipeline = new dev.luxloader.api.gpu.ComputePipelineDesc(
                        "ray-query-hit-test",
                        "(运行时编译)",
                        dev.luxloader.api.gpu.ComputePipelineDesc.ShaderStage.COMPUTE,
                        1,
                        List.of(
                                new dev.luxloader.api.gpu.ComputePipelineDesc.Binding(0,
                                        dev.luxloader.api.gpu.ComputePipelineDesc.DescriptorType
                                                .ACCELERATION_STRUCTURE,
                                        1, "scene"),
                                new dev.luxloader.api.gpu.ComputePipelineDesc.Binding(1,
                                        dev.luxloader.api.gpu.ComputePipelineDesc.DescriptorType
                                                .STORAGE_BUFFER,
                                        1, "result")),
                        1, 1, 1,
                        "main");
                long vkPipeline = device.createComputePipeline(pipeline, spirv);
                assertTrue(vkPipeline != 0L, "Compute pipeline creation failed with acceleration-structure bindings");

                var commands = device.commands();
                var cmd = commands.begin("ray-query");
                cmd.bindComputePipeline(pipeline);
                cmd.writeAccelStruct("scene", tlas);
                cmd.writeBuffer("result", result, 0L, 2 * 4L);
                cmd.dispatch(1, 1, 1);
                cmd.end();
                commands.flush(device.graphicsQueue(), List.of(), List.of());

                ByteBuffer read = ((VulkanCommands) commands).readBufferBlocking(result.bits(), 0L, 2 * 4L);
                read.order(ByteOrder.nativeOrder());
                float t = read.getFloat(0);
                float prim = read.getFloat(4);
                System.out.println("[Ray query] Readback: t=" + t + " primitive=" + prim);

                assertTrue(t > 0f, "Ray missed (t=" + t + "; -1 means the committed status was not a triangle hit)");
                assertEquals(1.0f, t, 1e-3f,
                        "Expected analytic distance 1.0 from z=-1 to the triangle at z=0");
                assertEquals(0.0f, prim, 0.5f, "Expected primitive index zero");

                provider.release(blas);
                provider.release(tlas);
            } finally {
                provider.closeAll();
            }
        } finally {
            device.close();
        }
    }
}
