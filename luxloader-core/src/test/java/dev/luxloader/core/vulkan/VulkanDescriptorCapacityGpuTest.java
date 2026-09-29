package dev.luxloader.core.vulkan;

import dev.luxloader.api.gpu.*;
import dev.luxloader.core.diag.DiagnosticsImpl;
import dev.luxloader.shader.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/** Exercises many terrain-style draws, immutable descriptor snapshots, and next-frame reuse. */
class VulkanDescriptorCapacityGpuTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manyDrawsKeepBindingsAcrossPoolGrowthAndFrameReset(boolean changeBindings) throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        var work = Files.createTempDirectory("lux-descriptor-capacity");
        String explicit = System.getProperty("luxloader.test.slangc", "");
        var compiler = SlangCompiler.detect(explicit.isBlank() ? null : Path.of(explicit), work);
        assumeTrue(compiler.isPresent());
        var slang = compiler.orElseThrow();
        try {
            var vertex = slang.compile(ShaderSource.FromSource.of("capacity-vs", ShaderStage.VERTEX, """
                    [shader("vertex")]
                    float4 main(uint id : SV_VertexID) : SV_Position {
                        float2 p = id == 0 ? float2(-1,-1) : id == 1 ? float2(3,-1) : float2(-1,3);
                        return float4(p,0,1);
                    }
                    """), List.of());
            var fragment = slang.compile(ShaderSource.FromSource.of("capacity-fs", ShaderStage.FRAGMENT, """
                    [[vk::binding(0,0)]] StructuredBuffer<float4> values;
                    [[vk::binding(1,0)]] Texture2D<float4> textures[64];
                    struct Draw { uint column; };
                    [[vk::push_constant]] ConstantBuffer<Draw> draw;
                    [shader("fragment")]
                    float4 main(float4 position : SV_Position) : SV_Target {
                        if (uint(position.x) != draw.column) discard;
                        float sample = textures[draw.column % 64].Load(int3(0,0,0)).g;
                        return float4(values[0].r, sample, float(draw.column)/255.0, 1.0);
                    }
                    """), List.of());
            assertTrue(vertex.success(), vertex.report());
            assertTrue(fragment.success(), fragment.report());
            var diagnostics = new DiagnosticsImpl(work, "descriptor-capacity", true);
            var device = VulkanDevice.create("Descriptor capacity test", true, diagnostics);
            assertNotNull(device);
            try (device; var resources = new VulkanResourceProvider(device, "descriptor-capacity", 0)) {
                final int draws = 192;
                int alignment = 256; // Covers the Vulkan maximum minStorageBufferOffsetAlignment.
                var values = resources.buffer(BufferDesc.of("values", (long) draws * alignment,
                        BufferDesc.Usage.STORAGE, BufferDesc.Usage.TRANSFER_DST));
                var source = resources.image(ImageDesc.builder("source", 1, 1, GpuFormat.R8G8B8A8_UNORM)
                        .usage(ImageDesc.Usage.SAMPLED, ImageDesc.Usage.TRANSFER_DST).build());
                var output = resources.image(ImageDesc.builder("output", draws, 4, GpuFormat.R8G8B8A8_UNORM)
                        .usage(ImageDesc.Usage.COLOR_ATTACHMENT, ImageDesc.Usage.TRANSFER_SRC).build());
                var pipeline = new GraphicsPipelineDesc("capacity", "main", "main",
                        GpuFormat.R8G8B8A8_UNORM, GpuFormat.UNDEFINED, 0, List.of(), false, false,
                        GraphicsPipelineDesc.DepthCompare.LESS_OR_EQUAL, false,
                        GraphicsPipelineDesc.CullMode.NONE, 4,
                        List.of(new ComputePipelineDesc.Binding(0,
                                        ComputePipelineDesc.DescriptorType.STORAGE_BUFFER, 1, "values"),
                                new ComputePipelineDesc.Binding(1,
                                        ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE_ARRAY, 64, "textures")));
                device.createGraphicsPipeline(pipeline, vertex.spirv(), fragment.spirv());
                var commands = (VulkanCommands) device.commands();
                for (int frame = 0; frame < 2; frame++) {
                    var cmd = commands.begin("capacity-frame-" + frame);
                    var data = ByteBuffer.allocate(draws * alignment).order(ByteOrder.nativeOrder());
                    for (int i = 0; i < draws; i++) data.putFloat(i * alignment, (i + frame * 32) / 255f);
                    cmd.updateBuffer(values, 0, data.array());
                    cmd.clearColor(source, null, 0, frame == 0 ? 0.25f : 0.75f, 0, 1);
                    cmd.writeImages("textures", Collections.nCopies(64, source),
                            Collections.nCopies(64, ImageViewDesc.sampled(GpuFormat.R8G8B8A8_UNORM)));
                    cmd.writeBuffer("values", values, 0, 16);
                    cmd.beginRenderPass(new RenderPassDesc("capacity",
                            List.of(RenderPassDesc.Attachment.colorClear(output, 0, 0, 0, 1)), null, draws, 4));
                    cmd.bindGraphicsPipeline(pipeline);
                    for (int i = 0; i < draws; i++) {
                        if (changeBindings) cmd.writeBuffer("values", values, (long) i * alignment, 16);
                        cmd.pushConstants(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(i).array());
                        // Repeated draws must retain the same set; changed offsets need fresh immutable sets.
                        for (int repeat = 0; repeat < 8; repeat++) cmd.draw(3, 1, 0, 0);
                    }
                    cmd.endRenderPass().end();
                    commands.submit(device.graphicsQueue(), List.of(cmd), List.of(), List.of());
                    commands.waitIdleAll();
                    byte[] pixels = commands.readImageBlocking(output.bits(),
                            GpuFormat.R8G8B8A8_UNORM.vkFormat(), draws, 4);
                    for (int i = 0; i < draws; i++) {
                        assertEquals((changeBindings ? i : 0) + frame * 32, pixels[i * 4] & 255, 1,
                                "descriptor snapshot at column " + i + ", frame " + frame);
                        assertEquals(frame == 0 ? 64 : 191, pixels[i * 4 + 1] & 255, 1);
                        assertEquals(i, pixels[i * 4 + 2] & 255, 1, "push constants at column " + i);
                    }
                    commands.resetDescriptorPools();
                }
            }
        } finally { slang.close(); }
    }
}
