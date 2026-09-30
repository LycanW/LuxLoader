package dev.luxloader.mc.hooks;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceMixinApplicationTest {
    @Test void resourceAndGeometryHooksApplyToRealMinecraftBytecode() throws Exception {
        String[] classes = {"net.minecraft.server.packs.resources.ReloadableResourceManager",
                "net.minecraft.client.renderer.chunk.SectionCompiler$Results",
                "net.minecraft.client.renderer.chunk.SectionCompiler",
                "net.minecraft.client.renderer.chunk.CompiledSectionMesh",
                "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection"};
        for (String name : classes) {
            byte[] transformed = OfflineMixinHarness.transform(name, Path.of("build/r1-mixin"));
            var node = new ClassNode(); new ClassReader(transformed).accept(node, 0);
            if (name.endsWith("ReloadableResourceManager")) {
                assertEquals(1, calls(node, "dev/luxloader/mc/MinecraftResourceAccess", "trackReload"));
                assertEquals(1, calls(node, "dev/luxloader/mc/MinecraftResourceAccess", "closed"));
            } else if (name.endsWith("$Results") || name.endsWith("CompiledSectionMesh")) {
                assertTrue(node.interfaces.contains("dev/luxloader/mc/hooks/SectionResourceGeneration"), name);
            } else {
                assertTrue(calls(node, "dev/luxloader/mc/MinecraftResourceAccess", "currentState") >= 1, name);
                if (name.endsWith("$RenderSection")) {
                    assertEquals(1, calls(node, "dev/luxloader/mc/MinecraftCompiledScene", "acceptSection"));
                    assertTrue(calls(node, "dev/luxloader/mc/MinecraftCompiledScene", "captureIncomplete") >= 3,
                            "Late generation, untagged layers and failed capture must all retain the host path");
                }
            }
        }
    }

    private static long calls(ClassNode node, String owner, String name) {
        long count = 0;
        for (var method : node.methods) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) count++;
        return count;
    }
}
