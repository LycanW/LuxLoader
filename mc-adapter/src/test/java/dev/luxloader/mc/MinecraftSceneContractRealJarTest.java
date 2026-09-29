package dev.luxloader.mc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/** Checks extraction/animation member shapes without initializing or launching Minecraft. */
class MinecraftSceneContractRealJarTest {
    @Test void resourceEnvironmentAndFeatureMembersMatchTheInstalledClient() throws Exception {
        var jar = MinecraftClientJar.resolve();
        assumeTrue(jar != null);
        try (var loader = MinecraftClientJar.classLoaderFor(jar)) {
            Class<?> languageManager = loader.loadClass("net.minecraft.client.resources.language.LanguageManager");
            assertEquals(String.class, languageManager.getMethod("getSelected").getReturnType());
            assertEquals(void.class, languageManager.getMethod("setSelected", String.class).getReturnType());
            Class<?> nativeDevice = loader.loadClass("com.mojang.renderpearl.backend.vulkan.VulkanDevice")
                    .getMethod("vkDevice").getReturnType();
            assertNotNull(nativeDevice.getMethod("getPhysicalDevice").getReturnType().getMethod("address"));
            assertEquals(int.class, nativeDevice.getMethod("getCapabilitiesInstance").getReturnType()
                    .getField("apiVersion").getType());
            assertNotNull(loader.loadClass("com.mojang.renderpearl.backend.vulkan.VulkanInstance")
                    .getMethod("getEnabledExtensions"));
            assertNotNull(loader.loadClass("net.minecraft.client.renderer.GameRenderer").getMethod("renderLevel"));
            assertEquals("com.mojang.renderpearl.api.buffers.GpuBufferSlice",
                    loader.loadClass("net.minecraft.client.renderer.ProjectionMatrixBuffer")
                            .getMethod("getBuffer", loader.loadClass("org.joml.Matrix4f")).getReturnType().getName());
            assertEquals(void.class, loader.loadClass("net.minecraft.client.renderer.feature.ShadowFeatureRenderer")
                    .getDeclaredMethod("buildGroup", loader.loadClass("net.minecraft.client.renderer.feature.FeatureFrameContext"),
                            java.util.List.class).getReturnType());
            assertDoesNotThrow(() -> new MinecraftEnvironmentAccess(loader, ignored -> {}));
            assertDoesNotThrow(() -> new MinecraftResourceAccess(loader));
            Class<?> state = Class.forName("net.minecraft.client.renderer.texture.SpriteContents$AnimationState", false, loader);
            assertEquals(int.class, state.getDeclaredField("frame").getType());
            assertEquals(int.class, state.getDeclaredField("subFrame").getType());
            Class<?> info = state.getDeclaredField("animationInfo").getType();
            assertNotNull(info.getDeclaredField("frames"));
            assertEquals(boolean.class, info.getDeclaredField("interpolateFrames").getType());
            assertNotNull(info.getDeclaredField("this$0").getType().getDeclaredField("originalImage"));
            Class<?> frame = Class.forName("net.minecraft.client.renderer.texture.SpriteContents$FrameInfo", false, loader);
            assertEquals(int.class, frame.getDeclaredField("index").getType());
            assertEquals(int.class, frame.getDeclaredField("time").getType());
            Class<?> draw = Class.forName("com.mojang.blaze3d.vertex.MeshData", false, loader);
            assertNotNull(draw.getMethod("indexBuffer"));
            Class<?> fog = Class.forName("net.minecraft.client.renderer.fog.FogData", false, loader);
            for (String member : java.util.List.of("environmentalStart", "environmentalEnd", "renderDistanceStart", "renderDistanceEnd"))
                assertEquals(float.class, fog.getField(member).getType());
        }
    }
}
