package dev.luxloader.core.vulkan;

import dev.luxloader.api.gpu.GpuFormat;
import dev.luxloader.api.gpu.ComputePipelineDesc;
import dev.luxloader.api.gpu.GraphicsPipelineDesc;
import dev.luxloader.api.gpu.ImageDesc;
import dev.luxloader.api.gpu.ImageViewDesc;
import dev.luxloader.api.gpu.RenderPassDesc;
import dev.luxloader.shader.ShaderSource;
import dev.luxloader.shader.ShaderStage;
import dev.luxloader.shader.SlangCompiler;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in hardware test: an actual raster pass writes a verifiable pixel. */
class VulkanGraphicsSmokeTest {
    private static final String VERTEX = """
            [shader("vertex")]
            float4 main(uint vertexId : SV_VertexID) : SV_Position {
                float2 p = vertexId == 0 ? float2(-1.0, -1.0)
                         : vertexId == 1 ? float2(3.0, -1.0) : float2(-1.0, 3.0);
                return float4(p, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            [shader("fragment")]
            float4 main() : SV_Target { return float4(0.25, 0.5, 0.75, 1.0); }
            """;

    @Test
    void rasterDrawsToColorAttachment() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        assumeTrue(VulkanApi.isAvailable());
        var work = Files.createTempDirectory("luxloader-graphics-smoke");
        var compiler = SlangCompiler.detect(null, work);
        assumeTrue(compiler.isPresent(), "Slang compiler unavailable");
        var slang = compiler.orElseThrow();
        try {
            var vertex = slang.compile(ShaderSource.FromSource.of("graphics-smoke-vs",
                    ShaderStage.VERTEX, VERTEX), List.of());
            var fragment = slang.compile(ShaderSource.FromSource.of("graphics-smoke-fs",
                    ShaderStage.FRAGMENT, FRAGMENT), List.of());
            assertTrue(vertex.success(), vertex.report());
            assertTrue(fragment.success(), fragment.report());
            var diagnostics = new dev.luxloader.core.diag.DiagnosticsImpl(
                    work, "graphics-smoke", true);
            var device = VulkanDevice.create("LuxLoader Graphics Test", true, diagnostics);
            assumeTrue(device != null && (device.capabilities().apiMajor() > 1
                    || device.capabilities().apiMinor() >= 3), "Dynamic rendering requires Vulkan 1.3");
            try (var resources = new VulkanResourceProvider(device, "graphics-smoke", 0L)) {
                var color = resources.image(ImageDesc.builder("color", 16, 16,
                                GpuFormat.R8G8B8A8_UNORM)
                        .usage(ImageDesc.Usage.COLOR_ATTACHMENT, ImageDesc.Usage.TRANSFER_SRC)
                        .build());
                var pipeline = new GraphicsPipelineDesc("solid-triangle", "main", "main",
                        GpuFormat.R8G8B8A8_UNORM, GpuFormat.UNDEFINED, 0, List.of(),
                        false, false, GraphicsPipelineDesc.DepthCompare.LESS_OR_EQUAL,
                        false, GraphicsPipelineDesc.CullMode.NONE, 0);
                assertNotEquals(0L, device.createGraphicsPipeline(pipeline,
                        vertex.spirv(), fragment.spirv()));
                var commands = device.commands();
                var buffer = commands.begin("raster-smoke");
                buffer.beginRenderPass(new RenderPassDesc("raster-smoke",
                        List.of(RenderPassDesc.Attachment.colorClear(color, 0f, 0f, 0f, 1f)),
                        null, 16, 16));
                buffer.bindGraphicsPipeline(pipeline).draw(3, 1, 0, 0);
                buffer.endRenderPass().end();
                commands.submit(device.graphicsQueue(), List.of(buffer), List.of(), List.of());
                commands.waitIdle(device.graphicsQueue());
                int[] pixel = ((VulkanCommands) commands).readPixelBlocking(
                        color.bits(), GpuFormat.R8G8B8A8_UNORM.vkFormat(), 8, 8);
                assertEquals(64, pixel[0], 2);
                assertEquals(128, pixel[1], 2);
                assertEquals(191, pixel[2], 2);
            } finally {
                device.close();
            }
        } finally {
            slang.close();
        }
    }

    @Test
    void loadedAttachmentKeepsTheBlittedScene() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        assumeTrue(VulkanApi.isAvailable());
        var work = Files.createTempDirectory("luxloader-graphics-load");
        var compiler = SlangCompiler.detect(null, work);
        assumeTrue(compiler.isPresent(), "Slang compiler unavailable");
        var slang = compiler.orElseThrow();
        try {
            var vertex = slang.compile(ShaderSource.FromSource.of("graphics-load-vs",
                    ShaderStage.VERTEX, VERTEX), List.of());
            var fragment = slang.compile(ShaderSource.FromSource.of("graphics-load-fs",
                    ShaderStage.FRAGMENT, """
                            [shader("fragment")]
                            float4 main() : SV_Target { return float4(1.0, 0.0, 0.0, 0.5); }
                            """), List.of());
            assertTrue(vertex.success(), vertex.report());
            assertTrue(fragment.success(), fragment.report());
            var diagnostics = new dev.luxloader.core.diag.DiagnosticsImpl(work,
                    "graphics-load", true);
            var device = VulkanDevice.create("LuxLoader Graphics Load Test", true, diagnostics);
            assumeTrue(device != null && (device.capabilities().apiMajor() > 1
                    || device.capabilities().apiMinor() >= 3), "Dynamic rendering requires Vulkan 1.3");
            try (var resources = new VulkanResourceProvider(device, "graphics-load", 0L)) {
                var source = resources.image(ImageDesc.builder("source", 16, 16,
                                GpuFormat.R8G8B8A8_UNORM)
                        .usage(ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                        .build());
                var output = resources.image(ImageDesc.builder("output", 16, 16,
                                GpuFormat.R8G8B8A8_UNORM)
                        .usage(ImageDesc.Usage.TRANSFER_DST, ImageDesc.Usage.TRANSFER_SRC,
                                ImageDesc.Usage.COLOR_ATTACHMENT)
                        .build());
                var pipeline = new GraphicsPipelineDesc("loaded-scene", "main", "main",
                        GpuFormat.R8G8B8A8_UNORM, GpuFormat.UNDEFINED, 0, List.of(),
                        false, false, GraphicsPipelineDesc.DepthCompare.LESS_OR_EQUAL,
                        true, GraphicsPipelineDesc.CullMode.NONE, 0);
                device.createGraphicsPipeline(pipeline, vertex.spirv(), fragment.spirv());
                var commands = device.commands();
                // Minecraft supplies a host-owned scene image that stays in GENERAL.
                ((VulkanCommands) commands).registerExternalImage(source.bits(),
                        GpuFormat.R8G8B8A8_UNORM.vkFormat());
                var buffer = commands.begin("loaded-scene");
                buffer.clearColor(source, null, 0f, 0.5f, 0.25f, 1f);
                buffer.blitImage(source, null, 16, 16, output, null, 16, 16);
                buffer.beginRenderPass(RenderPassDesc.to(output).withViewport(16, 16));
                buffer.bindGraphicsPipeline(pipeline).draw(3, 1, 0, 0);
                buffer.endRenderPass().end();
                commands.submit(device.graphicsQueue(), List.of(buffer), List.of(), List.of());
                commands.waitIdle(device.graphicsQueue());
                int[] pixel = ((VulkanCommands) commands).readPixelBlocking(output.bits(),
                        GpuFormat.R8G8B8A8_UNORM.vkFormat(), 8, 8);
                assertEquals(128, pixel[0], 2);
                assertEquals(64, pixel[1], 2);
                assertEquals(32, pixel[2], 2);
            } finally {
                device.close();
            }
        } finally {
            slang.close();
        }
    }

    @Test
    void fragmentShaderReadsASceneTexture() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        assumeTrue(VulkanApi.isAvailable());
        var work = Files.createTempDirectory("luxloader-graphics-texture");
        var compiler = SlangCompiler.detect(null, work);
        assumeTrue(compiler.isPresent(), "Slang compiler unavailable");
        var slang = compiler.orElseThrow();
        try {
            var vertex = slang.compile(ShaderSource.FromSource.of("graphics-texture-vs",
                    ShaderStage.VERTEX, VERTEX), List.of());
            var fragment = slang.compile(ShaderSource.FromSource.of("graphics-texture-fs",
                    ShaderStage.FRAGMENT, """
                            [[vk::binding(0, 0)]] Texture2D<float4> gSource;
                            [shader("fragment")]
                            float4 main(float4 position : SV_Position) : SV_Target {
                                return gSource.Load(int3(int2(position.xy), 0));
                            }
                            """), List.of());
            assertTrue(vertex.success(), vertex.report());
            assertTrue(fragment.success(), fragment.report());
            var diagnostics = new dev.luxloader.core.diag.DiagnosticsImpl(work,
                    "graphics-texture", true);
            var device = VulkanDevice.create("LuxLoader Graphics Texture Test", true, diagnostics);
            assumeTrue(device != null && (device.capabilities().apiMajor() > 1
                    || device.capabilities().apiMinor() >= 3));
            try (var resources = new VulkanResourceProvider(device, "graphics-texture", 0L)) {
                var source = resources.image(ImageDesc.builder("scene", 16, 16,
                                GpuFormat.R8G8B8A8_UNORM)
                        .usage(ImageDesc.Usage.TRANSFER_DST, ImageDesc.Usage.SAMPLED)
                        .build());
                var output = resources.image(ImageDesc.builder("output", 16, 16,
                                GpuFormat.R8G8B8A8_UNORM)
                        .usage(ImageDesc.Usage.COLOR_ATTACHMENT, ImageDesc.Usage.TRANSFER_SRC)
                        .build());
                var pipeline = new GraphicsPipelineDesc("sample-scene", "main", "main",
                        GpuFormat.R8G8B8A8_UNORM, GpuFormat.UNDEFINED, 0, List.of(),
                        false, false, GraphicsPipelineDesc.DepthCompare.LESS_OR_EQUAL,
                        false, GraphicsPipelineDesc.CullMode.NONE, 0,
                        List.of(ComputePipelineDesc.Binding.sampled(0, "scene")));
                device.createGraphicsPipeline(pipeline, vertex.spirv(), fragment.spirv());
                var commands = device.commands();
                // The game supplies frame.color as a host-owned VkImage in GENERAL.
                // Exercise that path, not only provider-owned sampled textures.
                ((VulkanCommands) commands).registerExternalImage(source.bits(),
                        GpuFormat.R8G8B8A8_UNORM.vkFormat());
                var buffer = commands.begin("sample-scene");
                buffer.clearColor(source, null, 0f, 0.5f, 0.25f, 1f);
                buffer.writeImage("scene", source,
                        ImageViewDesc.sampled(GpuFormat.R8G8B8A8_UNORM));
                buffer.beginRenderPass(new RenderPassDesc("sample-scene",
                        List.of(RenderPassDesc.Attachment.colorClear(output, 0f, 0f, 0f, 1f)),
                        null, 16, 16));
                buffer.bindGraphicsPipeline(pipeline).draw(3, 1, 0, 0);
                buffer.endRenderPass().end();
                commands.submit(device.graphicsQueue(), List.of(buffer), List.of(), List.of());
                commands.waitIdle(device.graphicsQueue());
                int[] pixel = ((VulkanCommands) commands).readPixelBlocking(output.bits(),
                        GpuFormat.R8G8B8A8_UNORM.vkFormat(), 8, 8);
                assertEquals(0, pixel[0], 2);
                assertEquals(128, pixel[1], 2);
                assertEquals(64, pixel[2], 2);
            } finally {
                device.close();
            }
        } finally {
            slang.close();
        }
    }

    @Test
    void fragmentShaderReadsDepthAttachment() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        assumeTrue(VulkanApi.isAvailable());
        var work = Files.createTempDirectory("luxloader-graphics-depth");
        var compiler = SlangCompiler.detect(null, work);
        assumeTrue(compiler.isPresent(), "Slang compiler unavailable");
        var slang = compiler.orElseThrow();
        try {
            var vertex = slang.compile(ShaderSource.FromSource.of("graphics-depth-vs",
                    ShaderStage.VERTEX, VERTEX), List.of());
            var fragment = slang.compile(ShaderSource.FromSource.of("graphics-depth-fs",
                    ShaderStage.FRAGMENT, """
                            [[vk::binding(0, 0)]] Texture2D<float> gDepth;
                            [shader("fragment")]
                            float4 main(float4 position : SV_Position) : SV_Target {
                                float depth = gDepth.Load(int3(int2(position.xy), 0));
                                return float4(depth, depth, depth, 1.0);
                            }
                            """), List.of());
            assertTrue(vertex.success(), vertex.report());
            assertTrue(fragment.success(), fragment.report());
            var diagnostics = new dev.luxloader.core.diag.DiagnosticsImpl(work,
                    "graphics-depth", true);
            var device = VulkanDevice.create("LuxLoader Graphics Depth Test", true, diagnostics);
            assumeTrue(device != null && (device.capabilities().apiMajor() > 1
                    || device.capabilities().apiMinor() >= 3));
            try (var resources = new VulkanResourceProvider(device, "graphics-depth", 0L)) {
                var depth = resources.image(ImageDesc.builder("depth", 16, 16,
                                GpuFormat.D32_SFLOAT)
                        .usage(ImageDesc.Usage.DEPTH_STENCIL_ATTACHMENT, ImageDesc.Usage.SAMPLED)
                        .build());
                var output = resources.image(ImageDesc.builder("output", 16, 16,
                                GpuFormat.R8G8B8A8_UNORM)
                        .usage(ImageDesc.Usage.COLOR_ATTACHMENT, ImageDesc.Usage.TRANSFER_SRC)
                        .build());
                var pipeline = new GraphicsPipelineDesc("sample-depth", "main", "main",
                        GpuFormat.R8G8B8A8_UNORM, GpuFormat.UNDEFINED, 0, List.of(),
                        false, false, GraphicsPipelineDesc.DepthCompare.LESS_OR_EQUAL,
                        false, GraphicsPipelineDesc.CullMode.NONE, 0,
                        List.of(ComputePipelineDesc.Binding.sampled(0, "depth")));
                device.createGraphicsPipeline(pipeline, vertex.spirv(), fragment.spirv());
                var commands = device.commands();
                var buffer = commands.begin("sample-depth");
                buffer.beginRenderPass(new RenderPassDesc("clear-depth", List.of(),
                        new RenderPassDesc.Attachment(depth,
                                ImageViewDesc.sampled(GpuFormat.D32_SFLOAT),
                                RenderPassDesc.LoadOp.CLEAR, RenderPassDesc.StoreOp.STORE,
                                null, 0.75f), 16, 16));
                buffer.endRenderPass();
                buffer.writeImage("depth", depth,
                        ImageViewDesc.sampled(GpuFormat.D32_SFLOAT));
                buffer.beginRenderPass(new RenderPassDesc("read-depth",
                        List.of(RenderPassDesc.Attachment.colorClear(output, 0f, 0f, 0f, 1f)),
                        null, 16, 16));
                buffer.bindGraphicsPipeline(pipeline).draw(3, 1, 0, 0);
                buffer.endRenderPass().end();
                commands.submit(device.graphicsQueue(), List.of(buffer), List.of(), List.of());
                commands.waitIdle(device.graphicsQueue());
                int[] pixel = ((VulkanCommands) commands).readPixelBlocking(output.bits(),
                        GpuFormat.R8G8B8A8_UNORM.vkFormat(), 8, 8);
                assertEquals(191, pixel[0], 2);
                assertEquals(191, pixel[1], 2);
                assertEquals(191, pixel[2], 2);
            } finally {
                device.close();
            }
        } finally {
            slang.close();
        }
    }
}
