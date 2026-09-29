package dev.luxloader.core.vulkan;

import dev.luxloader.api.gpu.BufferDesc;
import dev.luxloader.api.gpu.ComputePipelineDesc;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.gpu.ImageDesc;
import dev.luxloader.api.gpu.ImageHandle;
import dev.luxloader.api.gpu.GpuFormat;
import dev.luxloader.api.pipeline.ResourceRequest;
import dev.luxloader.api.vulkan.ComputeBlendModule;
import dev.luxloader.api.vulkan.ComputeFillModule;
import dev.luxloader.api.vulkan.SpirvGen;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in Vulkan compute smoke tests covering device creation, resources, descriptors, pipeline
 * compilation, recording, submission and readback. Historical malformed handwritten SPIR-V crashed
 * Intel and AMD drivers; current modules are compiled with slangc. Run separately with
 * -Dluxloader.test.gpuPipeline=true because driver failures can terminate the JVM. Select a GPU with
 * -Dluxloader.test.gpuDevice=<name fragment>. Capability and allocation tests do not require pipeline
 * compilation.
 */
class VulkanComputeSmokeTest {

    /** Whether to run driver pipeline compilation tests; disabled by default. */
    private static final String ENABLE_PIPELINE_TEST = "luxloader.test.gpuPipeline";

    /** Dispatch one 64-thread workgroup with four elements per thread. */
    private static final int TOTAL_ELEMENTS = ComputeFillModule.LOCAL_SIZE_X * ComputeFillModule.ELEMENTS_PER_INVOCATION;

    private static final String FILL_SHADER = "fill-buffer";

    /** Optional GPU name fragment, for example -Dluxloader.test.gpuDevice=Radeon. */
    private static final String DEVICE_NAME = "luxloader.test.gpuDevice";

    /** Create a device with diagnostics exposing VkResult failures. */
    private static VulkanDevice createDeviceWithDiagnostics() {
        var diagnostics = new dev.luxloader.core.diag.DiagnosticsImpl(
                java.nio.file.Path.of("build", "luxloader-diagnostics"), "smoke-test", true);
        String wanted = System.getProperty(DEVICE_NAME, "").trim();
        VulkanDevice device = wanted.isEmpty()
                ? VulkanDevice.create("LuxLoader Test", true, diagnostics)
                : VulkanDevice.create("LuxLoader Test", true, diagnostics, wanted);
        if (device == null) {
            diagnostics.logLines().forEach(System.out::println);
        } else {
            // Print the actual device because an unmatched requested name can fall back to the default GPU.
            System.out.println("[GPU test] Actual device: " + device.capabilities().deviceName()
                    + (wanted.isEmpty() ? " (default selection)" : " (selected by \"" + wanted + "\")"));
        }
        return device;
    }

    @Test
    @DisplayName("Sampled Image Keeps Previous Frame Pixels")
    void sampledImageKeepsPreviousFramePixels() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());
        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");
        try (VulkanResourceProvider resources =
                     new VulkanResourceProvider(device, "sampled-history", 0L)) {
            ImageDesc desc = ImageDesc.builder("history", 8, 8, GpuFormat.R16G16B16A16_SFLOAT)
                    .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                            ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                    .build();
            ImageHandle history = resources.image(desc);
            ImageHandle seed = resources.image(ImageDesc.builder("seed", 8, 8,
                            GpuFormat.R16G16B16A16_SFLOAT)
                    .usage(ImageDesc.Usage.SAMPLED, ImageDesc.Usage.TRANSFER_DST)
                    .build());
            ImageHandle zero = resources.image(ImageDesc.builder("zero", 8, 8,
                            GpuFormat.R16G16B16A16_SFLOAT)
                    .usage(ImageDesc.Usage.SAMPLED, ImageDesc.Usage.TRANSFER_DST)
                    .build());
            ImageHandle output = resources.image(ImageDesc.storage("output", 8, 8,
                    GpuFormat.R16G16B16A16_SFLOAT));

            var commands = device.commands();
            var clear = commands.begin("history-seed");
            clear.clearColor(seed, dev.luxloader.api.gpu.ImageViewDesc.full(),
                    0.8f, 0.4f, 0.2f, 1f);
            clear.clearColor(zero, dev.luxloader.api.gpu.ImageViewDesc.full(),
                    0f, 0f, 0f, 1f);
            clear.end();
            commands.submit(device.graphicsQueue(), List.of(clear), List.of(), List.of());
            commands.waitIdle(device.graphicsQueue());

            ComputePipelineDesc pipeline = new ComputePipelineDesc(
                    "sampled-history", "(冻结的 blend.spv)",
                    ComputePipelineDesc.ShaderStage.COMPUTE, 16,
                    List.of(
                            new ComputePipelineDesc.Binding(0,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputA"),
                            new ComputePipelineDesc.Binding(1,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputB"),
                            new ComputePipelineDesc.Binding(2,
                                    ComputePipelineDesc.DescriptorType.STORAGE_IMAGE, 1, "output")),
                    8, 8, 1, "main");
            assertTrue(device.createComputePipeline(pipeline, ComputeBlendModule.bytes()) != 0L);

            var sampled = dev.luxloader.api.gpu.ImageViewDesc
                    .sampled(GpuFormat.R16G16B16A16_SFLOAT);
            var storage = dev.luxloader.api.gpu.ImageViewDesc
                    .storage(GpuFormat.R16G16B16A16_SFLOAT);
            var writeHistory = commands.begin("history-compute-write");
            writeHistory.bindComputePipeline(pipeline);
            writeHistory.writeImage("inputA", seed, sampled);
            writeHistory.writeImage("inputB", zero, sampled);
            writeHistory.writeImage("output", history, storage);
            writeHistory.pushConstants(new byte[16]);
            writeHistory.dispatch(1, 1, 1);
            writeHistory.end();
            commands.submit(device.graphicsQueue(), List.of(writeHistory), List.of(), List.of());
            commands.waitIdle(device.graphicsQueue());

            var compute = commands.begin("history-read-next-frame");
            compute.bindComputePipeline(pipeline);
            compute.writeImage("inputA", history, sampled);
            compute.writeImage("inputB", zero, sampled);
            compute.writeImage("output", output, storage);
            compute.pushConstants(new byte[16]); // mixFactor=0: output = history
            compute.dispatch(1, 1, 1);
            compute.end();
            commands.submit(device.graphicsQueue(), List.of(compute), List.of(), List.of());
            commands.waitIdle(device.graphicsQueue());

            int[] pixel = ((VulkanCommands) commands).readPixelBlocking(output.bits(), 97, 0, 0);
            float red = Float.float16ToFloat((short) (pixel[0] | (pixel[1] << 8)));
            assertEquals(0.8f, red, 0.01f, "Sampled input must preserve the previous frame's brightness");
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("Probes Capabilities")
    void probesCapabilities() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        VulkanApi.Probe probe = VulkanApi.probe("LuxLoader Test", true);
        Assumptions.assumeTrue(probe != null, "No usable Vulkan physical device found");

        try {
            GpuCapabilities caps = probe.capabilities();
            System.out.println("[GPU probe] Device=" + caps.deviceName()
                    + " vendor=" + caps.vendor().displayName()
                    + " Vulkan=" + caps.apiVersionString()
                    + " VRAM=" + caps.deviceMemoryGiB()
                    + " extensions=" + caps.extensions().size()
                    + " RT=" + caps.supportsRayTracing()
                    + " descriptorBuffer=" + caps.supportsDescriptorBuffer()
                    + " dedicatedComputeQueue=" + VulkanApi.hasDedicatedComputeQueue(probe.physicalDevice()));

            assertFalse(caps.deviceName().isBlank(), "Device name must not be empty");
            assertTrue(caps.apiMajor() >= 1, "Vulkan major version must be at least one");
            assertTrue(caps.maxImageDimension2D() > 0);
            assertTrue(caps.deviceMemoryBytes() > 0, "VRAM size must be available");

            // Vulkan 1.4 does not promote RT to core; report actual extension support.
            System.out.println("[GPU probe] RT extensions: VK_KHR_acceleration_structure="
                    + caps.supports("VK_KHR_acceleration_structure")
                    + " VK_KHR_ray_query=" + caps.supports("VK_KHR_ray_query")
                    + " VK_KHR_ray_tracing_pipeline=" + caps.supports("VK_KHR_ray_tracing_pipeline"));
        } finally {
            VulkanApi.destroyInstance(probe.instance());
        }
    }

    @Test
    @DisplayName("Cross Command Buffer Layout Visible To Validation")
    void crossCommandBufferLayoutVisibleToValidation() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        // Reproduce layout state crossing command-buffer boundaries: one buffer leaves the image in TRANSFER_SRC, and a new buffer uses the device-level tracked layout. Same-buffer tests do not cover this case.
        byte[] spirv = dev.luxloader.api.vulkan.ComputeBlendModule.bytes();
        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");
        try (VulkanResourceProvider resources =
                     new VulkanResourceProvider(device, "crossbuf-repro", 0L)) {

            ImageDesc desc = ImageDesc.builder("repro-crossbuf", 64, 64,
                            GpuFormat.R16G16B16A16_SFLOAT)
                    .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                            ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                    .build();
            dev.luxloader.api.gpu.ImageHandle image = resources.image(desc);
            assertFalse(image.isNull(), "Image creation failed");
            // Separate output image, exactly as the real pipeline has it. The earlier
            // version of this test bound input and output to ONE image; the storage binding
            // then moved it to GENERAL, staling the sampled declarations, and the result was
            // VUID-vkCmdDispatch-imageLayout-00344 -- a confound that told us nothing about
            // the real case.
            dev.luxloader.api.gpu.ImageHandle out = resources.image(
                    ImageDesc.builder("repro-crossbuf-out", 64, 64,
                                    GpuFormat.R16G16B16A16_SFLOAT)
                            .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                                    ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                            .build());
            assertFalse(out.isNull(), "Output image creation failed");

            ComputePipelineDesc pipeline = new ComputePipelineDesc(
                    "crossbuf-repro",
                    "(冻结的 blend.spv)",
                    ComputePipelineDesc.ShaderStage.COMPUTE,
                    16,
                    List.of(
                            new ComputePipelineDesc.Binding(0,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputA"),
                            new ComputePipelineDesc.Binding(1,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputB"),
                            new ComputePipelineDesc.Binding(2,
                                    ComputePipelineDesc.DescriptorType.STORAGE_IMAGE, 1, "output")),
                    8, 8, 1,
                    "main");
            long vkPipeline = device.createComputePipeline(pipeline, spirv);
            assertTrue(vkPipeline != 0L, "Compute pipeline compilation failed");

            var view = dev.luxloader.api.gpu.ImageViewDesc.full();
            var commands = device.commands();
            System.out.println("[Cross-buffer reproducer] image=0x" + Long.toHexString(image.bits()));

            // Buffer A: transition to TRANSFER_SRC and submit.
            var a = commands.begin("repro-A");
            a.transition(image, view,
                    dev.luxloader.api.gpu.GpuCommands.Access.NONE,
                    dev.luxloader.api.gpu.GpuCommands.Access.SHADER_READ);
            a.transition(image, view,
                    dev.luxloader.api.gpu.GpuCommands.Access.SHADER_READ,
                    dev.luxloader.api.gpu.GpuCommands.Access.SHADER_WRITE);
            a.transition(image, view,
                    dev.luxloader.api.gpu.GpuCommands.Access.SHADER_WRITE,
                    dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_READ);
            a.end();
            commands.submit(device.graphicsQueue(), List.of(a), List.of(), List.of());
            commands.waitIdle(device.graphicsQueue());
            System.out.println("[Cross-buffer reproducer] A submitted in TRANSFER_SRC_OPTIMAL");

            // Buffer B: resume from TRANSFER_SRC, write descriptors and dispatch.
            var b = commands.begin("repro-B");
            b.transition(image, view,
                    dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_READ,
                    dev.luxloader.api.gpu.GpuCommands.Access.SHADER_READ);
            b.bindComputePipeline(pipeline);
            b.writeImage("inputA", image,
                    dev.luxloader.api.gpu.ImageViewDesc.sampled(GpuFormat.R16G16B16A16_SFLOAT));
            b.writeImage("inputB", image,
                    dev.luxloader.api.gpu.ImageViewDesc.sampled(GpuFormat.R16G16B16A16_SFLOAT));
            b.writeImage("output", out,
                    dev.luxloader.api.gpu.ImageViewDesc.storage(GpuFormat.R16G16B16A16_SFLOAT));
            b.pushConstants(new byte[16]);
            b.dispatch(1, 1, 1);
            b.end();
            commands.submit(device.graphicsQueue(), List.of(b), List.of(), List.of());
            commands.waitIdle(device.graphicsQueue());
            System.out.println("[Cross-buffer reproducer] B submitted. "
                    + "Search process output for 'expects VkImage'.");
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("Missing Layout Map Entry Visible To Validation")
    void missingLayoutMapEntryVisibleToValidation() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        // Historical validation reproducer for descriptor layout tracking. External images bypass transition(), while descriptors declare GENERAL. Tests isolated reports of VUID-vkCmdDraw-None-09600 to storage bindings whose current command buffer lacked a real layout transition. Layout-preserving GENERAL-to-GENERAL barriers did not establish tracking entries in the observed validation layer. These observations explain the external-image case only; separate internally allocated images also produced reports and required further investigation. Run this method independently to inspect the validation messages rather than interpreting it as proof that all live-game layout issues are resolved.
        byte[] spirv = dev.luxloader.api.vulkan.ComputeBlendModule.bytes();
        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");
        try (VulkanResourceProvider resources =
                     new VulkanResourceProvider(device, "nolayout-repro", 0L)) {

            ImageDesc desc = ImageDesc.builder("repro-nolayout", 64, 64,
                            GpuFormat.R16G16B16A16_SFLOAT)
                    .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                            ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                    .build();
            dev.luxloader.api.gpu.ImageHandle image = resources.image(desc);
            assertFalse(image.isNull(), "Image creation failed");

            var commands = device.commands();
            ((VulkanCommands) commands).registerExternalImage(image.bits(), 97);
            System.out.println("[Missing-entry reproducer] image=0x" + Long.toHexString(image.bits())
                    + " registered as external; transition() skips its barriers");

            ComputePipelineDesc pipeline = new ComputePipelineDesc(
                    "nolayout-repro",
                    "(冻结的 blend.spv)",
                    ComputePipelineDesc.ShaderStage.COMPUTE,
                    16,
                    List.of(
                            new ComputePipelineDesc.Binding(0,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputA"),
                            new ComputePipelineDesc.Binding(1,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputB"),
                            new ComputePipelineDesc.Binding(2,
                                    ComputePipelineDesc.DescriptorType.STORAGE_IMAGE, 1, "output")),
                    8, 8, 1,
                    "main");
            long vkPipeline = device.createComputePipeline(pipeline, spirv);
            assertTrue(vkPipeline != 0L, "Compute pipeline compilation failed");

            var cmd = commands.begin("nolayout-repro");
            cmd.bindComputePipeline(pipeline);
            cmd.writeImage("inputA", image,
                    dev.luxloader.api.gpu.ImageViewDesc.sampled(GpuFormat.R16G16B16A16_SFLOAT));
            cmd.writeImage("inputB", image,
                    dev.luxloader.api.gpu.ImageViewDesc.sampled(GpuFormat.R16G16B16A16_SFLOAT));
            cmd.writeImage("output", image,
                    dev.luxloader.api.gpu.ImageViewDesc.storage(GpuFormat.R16G16B16A16_SFLOAT));
            cmd.pushConstants(new byte[16]);
            cmd.dispatch(1, 1, 1);
            cmd.end();

            commands.submit(device.graphicsQueue(), List.of(cmd), List.of(), List.of());
            commands.waitIdle(device.graphicsQueue());
            System.out.println("[Missing-entry reproducer] Submitted and completed. "
                    + "Search process output for 'expects VkImage'.");
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("Blit And Copy Visible To Validation")
    void blitAndCopyVisibleToValidation() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        // Reproduce two complete frame cycles with readPixelBlocking between them to exercise TRANSFER_SRC copy access, beyond transition-only tests.
        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");
        try (VulkanResourceProvider resources =
                     new VulkanResourceProvider(device, "copy-repro", 0L)) {

            ImageDesc desc = ImageDesc.builder("repro-copy", 64, 64,
                            GpuFormat.R16G16B16A16_SFLOAT)
                    .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                            ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                    .build();
            dev.luxloader.api.gpu.ImageHandle src = resources.image(desc);
            dev.luxloader.api.gpu.ImageHandle dst = resources.image(
                    ImageDesc.builder("repro-copy-dst", 64, 64, GpuFormat.R16G16B16A16_SFLOAT)
                            .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                                    ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                            .build());
            assertFalse(src.isNull(), "Source image creation failed");
            assertFalse(dst.isNull(), "Target image creation failed");

            var view = dev.luxloader.api.gpu.ImageViewDesc.full();
            var commands = device.commands();
            var vk = (VulkanCommands) commands;

            for (int frame = 1; frame <= 2; frame++) {
                var cmd = commands.begin("copy-repro-" + frame);
                // On frame two, the first barrier uses TRANSFER_SRC_OPTIMAL retained in the device-level layout table.
                cmd.transition(src, view,
                        dev.luxloader.api.gpu.GpuCommands.Access.NONE,
                        dev.luxloader.api.gpu.GpuCommands.Access.SHADER_READ);
                cmd.transition(src, view,
                        dev.luxloader.api.gpu.GpuCommands.Access.SHADER_READ,
                        dev.luxloader.api.gpu.GpuCommands.Access.SHADER_WRITE);
                cmd.blitImage(src, view, 64, 64, dst, view, 64, 64);
                cmd.transition(dst, view,
                        dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_WRITE,
                        dev.luxloader.api.gpu.GpuCommands.Access.SHADER_WRITE);
                cmd.end();
                commands.submit(device.graphicsQueue(), List.of(cmd), List.of(), List.of());
                commands.waitIdle(device.graphicsQueue());
                System.out.println("[Copy reproducer] Frame " + frame + " submitted");

                int[] px = vk.readPixelBlocking(src.bits(), 97, 0, 0);
                System.out.println("[Copy reproducer] Frame " + frame + " readback src(0,0)="
                        + java.util.Arrays.toString(px));
            }
            System.out.println("[Copy reproducer] Complete. Search process output for 'expects VkImage'.");
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("Descriptor Declaring General Visible To Validation")
    void descriptorDeclaringGeneralVisibleToValidation() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        // Extend the barrier-only reproducer with descriptor writes and dispatch using compiled blend.spv: sampled bindings 0/1, storage binding 2 and LocalSize 8x8x1.
        byte[] spirv = dev.luxloader.api.vulkan.ComputeBlendModule.bytes();
        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");
        try (VulkanResourceProvider resources =
                     new VulkanResourceProvider(device, "desc-repro", 0L)) {

            ImageDesc desc = ImageDesc.builder("repro-image", 64, 64,
                            GpuFormat.R16G16B16A16_SFLOAT)
                    .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                            ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                    .build();
            dev.luxloader.api.gpu.ImageHandle image = resources.image(desc);
            assertFalse(image.isNull(), "Image creation failed");
            // Output on its own image, as the real pipeline has it -- binding input and
            // output to one image stales the sampled declarations once the storage binding
            // moves it to GENERAL, which produces an unrelated 00344.
            dev.luxloader.api.gpu.ImageHandle outImage = resources.image(
                    ImageDesc.builder("repro-image-out", 64, 64,
                                    GpuFormat.R16G16B16A16_SFLOAT)
                            .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                                    ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                            .build());
            assertFalse(outImage.isNull(), "Output image creation failed");

            ComputePipelineDesc pipeline = new ComputePipelineDesc(
                    "desc-repro",
                    "(冻结的 blend.spv)",
                    ComputePipelineDesc.ShaderStage.COMPUTE,
                    16,
                    List.of(
                            new ComputePipelineDesc.Binding(0,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputA"),
                            new ComputePipelineDesc.Binding(1,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputB"),
                            new ComputePipelineDesc.Binding(2,
                                    ComputePipelineDesc.DescriptorType.STORAGE_IMAGE, 1, "output")),
                    8, 8, 1,
                    "main");
            long vkPipeline = device.createComputePipeline(pipeline, spirv);
            assertTrue(vkPipeline != 0L, "Compute pipeline compilation failed");
            System.out.println("[Descriptor reproducer] image=0x" + Long.toHexString(image.bits())
                    + " pipeline=0x" + Long.toHexString(vkPipeline));

            var view = dev.luxloader.api.gpu.ImageViewDesc
                    .storage(GpuFormat.R16G16B16A16_SFLOAT);
            var sampled = dev.luxloader.api.gpu.ImageViewDesc
                    .sampled(GpuFormat.R16G16B16A16_SFLOAT);
            var commands = device.commands();
            // Control the two variables the earlier versions of this test pinned at their
            // cleanest possible values -- which is exactly why they could not reproduce the
            // real case:
            //   1. frame count      -- the real run does ~1261, this did 1
            //   2. descriptor churn -- the real run allocates a FRESH descriptor set every
            //      frame out of a pool with maxSets=64; this did exactly one
            // The validation layer keeps its expected layout PER DESCRIPTOR SET
            // (vvl::DescriptorSet::UpdateImageLayoutDrawStates, read from the PDB symbol
            // table), so a recycled set carrying a stale snapshot is a live suspect.
            // "Unexplained" here meant "uncontrolled", not "random".
            int frames = 100;
            for (int frame = 1; frame <= frames; frame++) {
                var cmd = commands.begin("descriptor-repro-" + frame);
                cmd.bindComputePipeline(pipeline);
                cmd.writeImage("inputA", image, sampled);
                cmd.writeImage("inputB", image, sampled);
                cmd.writeImage("output", outImage, view);
                cmd.pushConstants(new byte[16]);
                cmd.dispatch(1, 1, 1);
                cmd.end();
                commands.submit(device.graphicsQueue(), List.of(cmd), List.of(), List.of());
                commands.waitIdle(device.graphicsQueue());
                // The real driver resets the descriptor pools at every frame boundary
                // (VulkanCommands.resetDescriptorPools -> vkResetDescriptorPool), which
                // IMPLICITLY FREES every set in the pool. The next frame's
                // vkAllocateDescriptorSets therefore hands back the same set handles.
                // The validation layer keeps its expected layout per descriptor set
                // (vvl::DescriptorSet::UpdateImageLayoutDrawStates), so a recycled set is
                // exactly the kind of variable that must be controlled rather than assumed
                // away. This repro never called it, so every frame got a virgin set -- which
                // is why it kept coming back clean.
                ((VulkanCommands) commands).resetDescriptorPools();
            }
            System.out.println("[Descriptor reproducer] " + frames + " frames submitted and completed. "
                    + "Search process output for 'expects VkImage'.");
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("Reproduces The Real Frame Sequence")
    void reproducesTheRealFrameSequence() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PIPELINE_TEST),
                "Requires -D" + ENABLE_PIPELINE_TEST + "=true; this GPU path can terminate the process");

        // Reproduce the live frame sequence: compute, pixel readback, presentation blit and restoration to GENERAL. Repeat offline to investigate early native crashes without the full game loop.
        byte[] spirv = dev.luxloader.api.vulkan.ComputeBlendModule.bytes();
        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");
        try (VulkanResourceProvider resources =
                     new VulkanResourceProvider(device, "frames-repro", 0L)) {

            ImageHandle source = resources.image(ImageDesc.builder("repro-frames-src", 854, 480,
                            GpuFormat.R16G16B16A16_SFLOAT)
                    .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                            ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                    .build());
            ImageHandle target = resources.image(ImageDesc.builder("repro-frames-dst", 854, 480,
                            GpuFormat.R8G8B8A8_UNORM)
                    .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                            ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                    .build());
            assertFalse(source.isNull() || target.isNull(), "Image creation failed");

            ComputePipelineDesc pipeline = new ComputePipelineDesc(
                    "frames-repro", "(冻结的 blend.spv)",
                    ComputePipelineDesc.ShaderStage.COMPUTE, 16,
                    List.of(
                            new ComputePipelineDesc.Binding(0,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputA"),
                            new ComputePipelineDesc.Binding(1,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputB"),
                            new ComputePipelineDesc.Binding(2,
                                    ComputePipelineDesc.DescriptorType.STORAGE_IMAGE, 1, "output")),
                    8, 8, 1, "main");
            long vkPipeline = device.createComputePipeline(pipeline, spirv);
            assertTrue(vkPipeline != 0L, "Compute pipeline compilation failed");

            var vk = (VulkanCommands) device.commands();
            var view = dev.luxloader.api.gpu.ImageViewDesc.full();
            var sampled = dev.luxloader.api.gpu.ImageViewDesc
                    .sampled(GpuFormat.R16G16B16A16_SFLOAT);
            var storage = dev.luxloader.api.gpu.ImageViewDesc
                    .storage(GpuFormat.R16G16B16A16_SFLOAT);
            int frames = 60;

            for (int frame = 1; frame <= frames; frame++) {
                // Compute: source is both input and output, matching the live path.
                var cmd = vk.begin("frames-compute-" + frame);
                cmd.bindComputePipeline(pipeline);
                cmd.writeImage("inputA", source, sampled);
                cmd.writeImage("inputB", source, sampled);
                cmd.writeImage("output", source, storage);
                cmd.pushConstants(new byte[16]);
                cmd.dispatch(1, 1, 1);
                cmd.end();
                vk.submit(device.graphicsQueue(), List.of(cmd), List.of(), List.of());
                vk.waitIdle(device.graphicsQueue());

                // Read one pixel, leaving source in TRANSFER_SRC_OPTIMAL.
                int[] px = vk.readPixelBlocking(source.bits(), 97, 0, 0);
                assertTrue(px != null && px.length > 0, "Readback must return pixel data");

                // Blit source into target after readback.
                var blit = vk.begin("frames-present-" + frame);
                blit.blitImage(source, view, 854, 480, target, view, 854, 480);
                blit.transition(target, view,
                        dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_WRITE,
                        dev.luxloader.api.gpu.GpuCommands.Access.SHADER_WRITE);
                blit.end();
                vk.submit(device.graphicsQueue(), List.of(blit), List.of(), List.of());
                vk.waitIdle(device.graphicsQueue());

                // Restore source to GENERAL.
                var restore = vk.begin("frames-restore-" + frame);
                restore.transition(source, view,
                        dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_READ,
                        dev.luxloader.api.gpu.GpuCommands.Access.SHADER_WRITE);
                restore.end();
                vk.submit(device.graphicsQueue(), List.of(restore), List.of(), List.of());
                vk.waitIdle(device.graphicsQueue());
            }
            System.out.println("[Frame-sequence reproducer] " + frames + " frames completed without a crash. "
                    + "A preceding native crash reproduces the failure offline.");
            // Historical result on 2026-09-23: 60 offline frames completed without image-layout reports or a crash. This narrows the investigation to differences introduced by host commands, presentation and swapchain integration; it does not prove the live path is correct.
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("Refuses Dispatch When ADeclared Binding Was Never Written")
    void refusesDispatchWhenADeclaredBindingWasNeverWritten() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        // Reject dispatches with unwritten descriptors before reaching the driver. The RT pass previously declared a depth binding when the host supplied no depth, producing VUID-vkCmdDispatch-None-08114 every frame. Use compiled blend.spv with sampled bindings 0/1 and storage binding 2.
        byte[] spirv = dev.luxloader.api.vulkan.ComputeBlendModule.bytes();
        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");
        try (VulkanResourceProvider resources =
                     new VulkanResourceProvider(device, "unwritten-repro", 0L)) {

            ImageHandle image = resources.image(ImageDesc.builder("unwritten-image", 64, 64,
                            GpuFormat.R16G16B16A16_SFLOAT)
                    .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                            ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                    .build());
            assertFalse(image.isNull(), "Image creation failed");
            ImageHandle outImage = resources.image(ImageDesc.builder("unwritten-image-out",
                            64, 64, GpuFormat.R16G16B16A16_SFLOAT)
                    .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                            ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                    .build());
            assertFalse(outImage.isNull(), "Output image creation failed");

            ComputePipelineDesc pipeline = new ComputePipelineDesc(
                    "unwritten-repro",
                    "(冻结的 blend.spv)",
                    ComputePipelineDesc.ShaderStage.COMPUTE,
                    16,
                    List.of(
                            new ComputePipelineDesc.Binding(0,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputA"),
                            new ComputePipelineDesc.Binding(1,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputB"),
                            new ComputePipelineDesc.Binding(2,
                                    ComputePipelineDesc.DescriptorType.STORAGE_IMAGE, 1, "output")),
                    8, 8, 1,
                    "main");
            assertTrue(device.createComputePipeline(pipeline, spirv) != 0L, "Compute pipeline compilation failed");

            var sampled = dev.luxloader.api.gpu.ImageViewDesc.sampled(GpuFormat.R16G16B16A16_SFLOAT);
            var storage = dev.luxloader.api.gpu.ImageViewDesc.storage(GpuFormat.R16G16B16A16_SFLOAT);

            // Declare three bindings but write only two, reproducing the missing-depth case.
            var cmd = device.commands().begin("unwritten-binding");
            cmd.bindComputePipeline(pipeline);
            cmd.writeImage("inputA", image, sampled);
            cmd.writeImage("output", outImage, storage); // Deliberately omit inputB.
            cmd.pushConstants(new byte[16]);
            var error = assertThrows(dev.luxloader.core.util.LuxException.class,
                    () -> cmd.dispatch(1, 1, 1),
                    "Missing bindings must prevent dispatch");
            String message = String.valueOf(error.getMessage());
            assertTrue(message.contains("binding 1"),
                    "The error must identify the binding: " + message);
            assertTrue(message.contains("inputB"),
                    "The error must include the resource name: " + message);
            System.out.println("[Contract check] Dispatch rejected: " + message);

            // Declare bindings without writing any resources. Descriptor validation must still run; the old path skipped allocation and binding entirely when the resource map was empty.
            var nothing = device.commands().begin("unwritten-nothing");
            nothing.bindComputePipeline(pipeline);
            nothing.pushConstants(new byte[16]);
            var error2 = assertThrows(dev.luxloader.core.util.LuxException.class,
                    () -> nothing.dispatch(1, 1, 1),
                    "Dispatch must fail when no bindings have been written");
            String message2 = String.valueOf(error2.getMessage());
            for (String expected : List.of("binding 0", "binding 1", "binding 2")) {
                assertTrue(message2.contains(expected),
                        "The error must list every unwritten binding; missing " + expected + "; actual: " + message2);
            }

            // Reject unsupported descriptor arrays. Writing only one image to a binding declared with count > 1 leaves remaining elements uninitialized; the current API cannot supply an image list under one name.
            ComputePipelineDesc arrayPipeline = new ComputePipelineDesc(
                    "array-repro",
                    "(冻结的 blend.spv)",
                    ComputePipelineDesc.ShaderStage.COMPUTE,
                    16,
                    List.of(
                            new ComputePipelineDesc.Binding(0,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE_ARRAY, 4, "inputA"),
                            new ComputePipelineDesc.Binding(1,
                                    ComputePipelineDesc.DescriptorType.SAMPLED_IMAGE, 1, "inputB"),
                            new ComputePipelineDesc.Binding(2,
                                    ComputePipelineDesc.DescriptorType.STORAGE_IMAGE, 1, "output")),
                    8, 8, 1,
                    "main");
            assertTrue(device.createComputePipeline(arrayPipeline, spirv) != 0L,
                    "Compute pipeline compilation failed");

            var arrayCmd = device.commands().begin("array-binding");
            arrayCmd.bindComputePipeline(arrayPipeline);
            arrayCmd.writeImage("inputA", image, sampled);
            arrayCmd.writeImage("inputB", image, sampled);
            arrayCmd.writeImage("output", outImage, storage);
            arrayCmd.pushConstants(new byte[16]);
            var arrayError = assertThrows(dev.luxloader.core.util.LuxException.class,
                    () -> arrayCmd.dispatch(1, 1, 1),
                    "Unsupported array bindings must reject dispatch instead of initializing only element zero");
            String arrayMessage = String.valueOf(arrayError.getMessage());
            assertTrue(arrayMessage.contains("binding 0"),
                    "The error must identify the binding: " + arrayMessage);
            assertTrue(arrayMessage.contains("inputA"),
                    "The error must include the resource name: " + arrayMessage);
            System.out.println("[Contract check] Array binding rejected: " + arrayMessage);
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("Layout Chain Visible To Validation")
    void layoutChainVisibleToValidation() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());

        // Isolate layout transitions on an offscreen device without a host, window, shaders or descriptors to distinguish barrier-sequence issues from game integration issues.
        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");
        try (VulkanResourceProvider resources =
                     new VulkanResourceProvider(device, "layout-repro", 0L)) {

            ImageDesc desc = ImageDesc.builder("repro-out", 64, 64, GpuFormat.R8G8B8A8_UNORM)
                    .usage(ImageDesc.Usage.STORAGE, ImageDesc.Usage.SAMPLED,
                            ImageDesc.Usage.TRANSFER_SRC, ImageDesc.Usage.TRANSFER_DST)
                    .build();
            dev.luxloader.api.gpu.ImageHandle image = resources.image(desc);
            assertFalse(image.isNull(), "Image creation failed");
            System.out.println("[Layout reproducer] image=0x" + Long.toHexString(image.bits())
                    + " (initialLayout = UNDEFINED from createImageInternal)");

            var view = dev.luxloader.api.gpu.ImageViewDesc.full();
            var commands = device.commands();
            var cmd = commands.begin("layout-chain");

            // Mirror writeImage, storage binding, blitImage and the next frame's first transition using the device-level tracked layout.
            cmd.transition(image, view,
                    dev.luxloader.api.gpu.GpuCommands.Access.NONE,
                    dev.luxloader.api.gpu.GpuCommands.Access.SHADER_READ);
            System.out.println("[Layout reproducer] 1 UNDEFINED -> SHADER_READ_ONLY_OPTIMAL");
            cmd.transition(image, view,
                    dev.luxloader.api.gpu.GpuCommands.Access.SHADER_READ,
                    dev.luxloader.api.gpu.GpuCommands.Access.SHADER_WRITE);
            System.out.println("[Layout reproducer] 2 SHADER_READ_ONLY -> GENERAL");
            cmd.transition(image, view,
                    dev.luxloader.api.gpu.GpuCommands.Access.SHADER_WRITE,
                    dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_READ);
            System.out.println("[Layout reproducer] 3 GENERAL -> TRANSFER_SRC_OPTIMAL");
            cmd.transition(image, view,
                    dev.luxloader.api.gpu.GpuCommands.Access.TRANSFER_READ,
                    dev.luxloader.api.gpu.GpuCommands.Access.SHADER_READ);
            System.out.println("[Layout reproducer] 4 TRANSFER_SRC_OPTIMAL -> SHADER_READ_ONLY_OPTIMAL");
            cmd.end();

            commands.submit(device.graphicsQueue(), List.of(cmd), List.of(), List.of());
            commands.waitIdle(device.graphicsQueue());
            System.out.println("[Layout reproducer] Submitted and completed. "
                    + "A matching VUID or 'expects VkImage' message reproduces the validation failure.");
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("Computes On Gpu")
    void computesOnGpu() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(),
                "Vulkan unavailable: " + VulkanApi.unavailableReason());
        // Skipped by default because pipeline compilation can crash some drivers; see the class documentation.
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PIPELINE_TEST),
                "Requires -D" + ENABLE_PIPELINE_TEST + "=true; some drivers can terminate the process on this path");

        // Load the precompiled shader module and validate its structure.
        byte[] spirv = ComputeFillModule.bytes();
        SpirvGen.Validation validation = SpirvGen.validate(spirv);
        if (!validation.valid()) {
            System.out.println("[GPU test] SPIR-V validation failed: " + validation.error());
            System.out.println(SpirvGen.describe(spirv));
        }
        assertTrue(validation.valid(),
                "The precompiled SPIR-V module is structurally invalid: " + validation.error());
        System.out.println("[GPU test] SPIR-V size=" + spirv.length + " bytes; instructions="
                + validation.instructionCount() + "; ID bound=" + validation.declaredBound());

        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");
        try {
            assertTrue(device.isOwned(), "The test device must be owned by the loader");
            assertTrue(device.nativeHandle() != 0L, "The device handle must be nonzero");
            System.out.println("[GPU test] Queues: graphics=" + device.graphicsQueue().describe()
                    + " compute=" + device.computeQueue().describe()
                    + " transfer=" + device.transferQueue().describe());

            // Allocate resources through the pipeline-scoped provider to exercise the public API.
            try (VulkanResourceProvider resources =
                         new VulkanResourceProvider(device, "smoke-test", 64L * 1024 * 1024)) {

                BufferDesc bufferDesc = BufferDesc.of("fill-target",
                        (long) TOTAL_ELEMENTS * Integer.BYTES,
                        BufferDesc.Usage.STORAGE, BufferDesc.Usage.TRANSFER_SRC);
                GpuDevice.Handle buffer = resources.buffer(bufferDesc);
                assertFalse(buffer.isNull(), "Buffer creation failed");
                assertEquals(BufferDesc.Usage.STORAGE, BufferDesc.Usage.STORAGE);
                assertTrue(bufferDesc.has(BufferDesc.Usage.STORAGE));

                // Identical buffer descriptions must reuse the cached resource.
                assertSame(buffer, resources.buffer(bufferDesc), "Identical descriptions must reuse the same buffer");

                // Initialize to zero to distinguish shader output from initial memory contents.
                ByteBuffer zeros = ByteBuffer.allocateDirect(TOTAL_ELEMENTS * Integer.BYTES);
                System.out.println("[GPU test] Step 1/6: upload zeroed data, " + zeros.capacity() + " bytes");
                resources.upload(buffer, 0, zeros);
                System.out.println("[GPU test] Step 1/6 complete");

                // Compile the compute pipeline with binding numbers matching the module, which uses binding 1.
                ComputePipelineDesc pipeline = new ComputePipelineDesc(
                        FILL_SHADER,
                        "(冻结的 SPIR-V 模块)",
                        ComputePipelineDesc.ShaderStage.COMPUTE,
                        0,
                        List.of(new ComputePipelineDesc.Binding(ComputeFillModule.BINDING,
                                ComputePipelineDesc.DescriptorType.STORAGE_BUFFER, 1, "data")),
                        ComputeFillModule.LOCAL_SIZE_X, 1, 1,
                        "main");

                System.out.println("[GPU test] Step 2/6: create compute pipeline");
                long vkPipeline = device.createComputePipeline(pipeline, spirv);
                assertTrue(vkPipeline != 0L, "Compute pipeline compilation failed");
                System.out.println("[GPU test] Step 2/6 complete; handle=0x" + Long.toHexString(vkPipeline));

                // Record and submit.
                var commands = device.commands();
                System.out.println("[GPU test] Step 3/6: begin recording");
                var cmd = commands.begin("fill-dispatch");
                System.out.println("[GPU test] Step 4/6: bind pipeline and resources");
                cmd.bindComputePipeline(pipeline);
                cmd.writeBuffer("data", buffer, 0, bufferDesc.size());
                System.out.println("[GPU test] Step 5/6: dispatch");
                cmd.dispatch(1, 1, 1);
                cmd.end();
                System.out.println("[GPU test] Step 5/6 complete");

                commands.submit(device.computeQueue(), List.of(cmd), List.of(), List.of());
                commands.waitIdle(device.computeQueue());

                // Read back and verify.
                int[] actual = readInts(device, buffer, TOTAL_ELEMENTS);

                int mismatches = 0;
                int firstBadIndex = -1;
                for (int i = 0; i < actual.length; i++) {
                    if (actual[i] != ComputeFillModule.FILL_VALUE) {
                        mismatches++;
                        if (firstBadIndex < 0) {
                            firstBadIndex = i;
                        }
                    }
                }

                assertTrue(mismatches == 0, String.format(
                        "Incorrect GPU output: %d/%d elements differ (expected 0x%08X; first mismatch index=%d, actual=0x%08X). "
                                + "Check descriptor bindings and command recording.",
                        mismatches, actual.length, ComputeFillModule.FILL_VALUE, firstBadIndex,
                        firstBadIndex >= 0 ? actual[firstBadIndex] : 0));

                System.out.println("[GPU test] Success: wrote and read back " + actual.length
                        + " uints, all equal to 0x" + Integer.toHexString(ComputeFillModule.FILL_VALUE));
            }
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("Creates Images")
    void createsImages() {
        Assumptions.assumeTrue(VulkanApi.isAvailable(), "Vulkan unavailable on this machine");
        VulkanDevice device = createDeviceWithDiagnostics();
        Assumptions.assumeTrue(device != null, "Could not create a Vulkan logical device");

        try (VulkanResourceProvider resources = new VulkanResourceProvider(device, "image-test", 0L)) {
            ImageDesc desc = ImageDesc.storage("history", 320, 180, GpuFormat.R16G16B16A16_SFLOAT);
            ImageHandle image = resources.image(desc);

            assertFalse(image.isNull(), "Texture creation failed");
            assertEquals(ImageHandle.Kind.VK_IMAGE, image.kind());
            assertTrue(image.label().contains("history"), "The label must contain the resource name: " + image.label());
            assertSame(image, resources.image(desc), "Identical descriptions must reuse the same texture");
            assertEquals(1, resources.imageCount());
            assertTrue(resources.usedVramBytes() > 0, "Account for VRAM allocation");

            // Create image views lazily and cache them.
            long view1 = resources.resolveView(image, null);
            long view2 = resources.resolveView(image, null);
            assertTrue(view1 != 0L, "Texture view creation failed");
            assertEquals(view1, view2, "Identical view descriptions must reuse a view");
            assertEquals(1, resources.viewCount());

            long storageView = resources.resolveView(image,
                    dev.luxloader.api.gpu.ImageViewDesc.storage(GpuFormat.R16G16B16A16_SFLOAT));
            assertTrue(storageView != 0L);
            assertNotEquals(view1, storageView, "Different view semantics must produce distinct views");
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("Estimates Resource Budget")
    void estimatesResourceBudget() {
        ResourceRequest request = ResourceRequest.builder()
                .image(ImageDesc.storage("hdr-history", 1920, 1080, GpuFormat.R16G16B16A16_SFLOAT))
                .image(ImageDesc.storage("motion", 1920, 1080, GpuFormat.R16G16_SFLOAT))
                .buffer(BufferDesc.of("constants", 4096, BufferDesc.Usage.UNIFORM))
                .accelStruct(dev.luxloader.api.gpu.AccelStructDesc.tlas("scene", 20000))
                .note("smoke test")
                .build();

        long bytes = request.estimatedBytes();
        assertTrue(bytes > 0);
        // Account for a 1920x1080x8 history buffer, motion vectors, 4 KB and estimated acceleration-structure storage.
        assertTrue(bytes > 24L * 1024 * 1024, "Estimated memory is too small; texture accounting may be missing: " + request.estimatedHuman());
        assertTrue(request.usesRayTracing(), "Declaring acceleration structures must count as RT usage");
        assertTrue(request.estimatedHuman().contains("MiB") || request.estimatedHuman().contains("GiB"));
        System.out.println("[Budget estimate] " + request.estimatedHuman());
    }

    private static int[] readInts(VulkanDevice device, GpuDevice.Handle buffer, int count) {
        // Read back through an explicit command; blocking is permitted in this test.
        ByteBuffer raw = device.readBufferBlocking(buffer.bits(), 0, (long) count * Integer.BYTES);
        try {
            int[] out = new int[count];
            raw.order(java.nio.ByteOrder.nativeOrder());
            for (int i = 0; i < count; i++) {
                out[i] = raw.getInt();
            }
            return out;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(raw);
        }
    }
}
