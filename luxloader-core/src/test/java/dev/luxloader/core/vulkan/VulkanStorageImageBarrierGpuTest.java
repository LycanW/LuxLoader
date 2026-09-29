package dev.luxloader.core.vulkan;

import dev.luxloader.api.gpu.*;
import dev.luxloader.core.diag.DiagnosticsImpl;
import dev.luxloader.shader.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class VulkanStorageImageBarrierGpuTest {
    @Test void storageWritesAreVisibleAcrossGeneralLayoutDispatches() throws Exception {
        assumeTrue(Boolean.getBoolean("luxloader.test.gpuPipeline"));
        var work = Files.createTempDirectory("lux-storage-barrier");
        String explicit = System.getProperty("luxloader.test.slangc", "");
        var compiler = SlangCompiler.detect(explicit.isBlank() ? null : Path.of(explicit), work);
        assumeTrue(compiler.isPresent());
        var slang = compiler.orElseThrow();
        try {
            var module = slang.compile(ShaderSource.FromSource.of("storage-read-modify-write", ShaderStage.COMPUTE, """
                    [[vk::binding(0,0)]] [[vk::image_format("r32f")]] RWTexture2D<float> state;
                    struct Step { uint iteration; }; [[vk::push_constant]] ConstantBuffer<Step> step;
                    [shader("compute")] [numthreads(8,8,1)]
                    void main(uint3 id : SV_DispatchThreadID) {
                        if (id.x >= 128 || id.y >= 128) return;
                        if (step.iteration == 0) state[id.xy] = float(id.x + id.y * 128);
                        else state[id.xy] = state[id.xy] + 1.0;
                    }
                    """), List.of());
            assertTrue(module.success(), module.report());
            var diagnostics = new DiagnosticsImpl(work, "storage-barrier", true);
            var device = VulkanDevice.create("Storage dependency test", true, diagnostics);
            assertNotNull(device);
            try (device; var resources = new VulkanResourceProvider(device, "storage-barrier", 0)) {
                var state = resources.image(ImageDesc.storage("state", 128, 128, GpuFormat.R32_SFLOAT));
                var desc = ComputePipelineDesc.compute("storage-steps", "test", 4,
                        List.of(ComputePipelineDesc.Binding.storage(0, "state")), 8, 8);
                device.createComputePipeline(desc, module.spirv());
                var commands = (VulkanCommands) device.commands();
                for (int i = 0; i <= 32; i++) {
                    var cmd = commands.begin("step-" + i);
                    cmd.transition(state, ImageViewDesc.storage(GpuFormat.R32_SFLOAT),
                            i == 0 ? GpuCommands.Access.NONE : GpuCommands.Access.SHADER_WRITE,
                            GpuCommands.Access.SHADER_WRITE);
                    cmd.bindComputePipeline(desc);
                    cmd.writeImage("state", state, ImageViewDesc.storage(GpuFormat.R32_SFLOAT));
                    cmd.pushConstants(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(i).array());
                    cmd.dispatch(16, 16, 1).end();
                }
                commands.flush(device.graphicsQueue(), List.of(), List.of());
                commands.waitIdleAll();
                var pixels = ByteBuffer.wrap(commands.readImageBlocking(state.bits(),
                        GpuFormat.R32_SFLOAT.vkFormat(), 128, 128)).order(ByteOrder.nativeOrder());
                for (int i = 0; i < 128 * 128; i++) assertEquals(i + 32f, pixels.getFloat(i * 4), 0f);
            }
        } finally { slang.close(); }
    }
}
