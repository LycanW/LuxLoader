package dev.luxloader.mc.hooks.mixin;

import static dev.luxloader.api.i18n.Messages.tr;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.luxloader.api.scene.CompiledSceneMesh;
import dev.luxloader.api.scene.MeshChunk;
import dev.luxloader.mc.MinecraftCompiledScene;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Copies the accepted compiled section data while the game's upload buffers still exist. */
@Mixin(value = SectionRenderDispatcher.RenderSection.class, remap = false)
public class SectionMeshCaptureMixin {

    private static boolean captureFailureReported;

    @Inject(method = "addSectionBuffersToUberBuffer", at = @At("RETURN"), require = 0)
    private void luxloader$captureSectionLayer(ChunkSectionLayer layer,
            CompiledSectionMesh compiled, ByteBuffer vertexBuffer, ByteBuffer indexBuffer,
            CallbackInfoReturnable<Boolean> cir) {
        if (vertexBuffer == null) {
            // A transparency resort can upload indices only; the vertices are unchanged.
            return;
        }
        try {
            var state = dev.luxloader.mc.MinecraftResourceAccess.currentState();
            if (!state.ready() || ((dev.luxloader.mc.hooks.SectionResourceGeneration) compiled)
                    .luxloader$resourceGeneration() != state.generation()) return;
            SectionMesh.SectionDraw draw = compiled.getSectionDraw(layer);
            if (draw == null) {
                return;
            }
            var section = (SectionRenderDispatcher.RenderSection) (Object) this;
            long node = section.getSectionNode();
            VertexFormat format = layer.vertexFormat();
            List<CompiledSceneMesh.Attribute> attributes = new ArrayList<>();
            for (var element : format.getElements()) {
                attributes.add(new CompiledSceneMesh.Attribute(
                        element.name(), element.offset(), element.format().name()));
            }
            byte[] vertices = copy(vertexBuffer);
            int stride = format.getVertexSize();
            float[] emission = ((dev.luxloader.mc.hooks.SectionEmission) compiled).luxloader$emissionLayers().get(layer);
            if (emission != null && emission.length == vertices.length / stride) {
                var packed = ByteBuffer.allocate(emission.length * (stride + 4)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (int vertex = 0; vertex < emission.length; vertex++)
                    packed.put(vertices, vertex * stride, stride).putFloat(emission[vertex]);
                attributes.add(new CompiledSceneMesh.Attribute("Emission", stride, "R32_FLOAT"));
                stride += 4;
                vertices = packed.array();
            }
            byte[] indices = indexBuffer == null ? new byte[0] : copy(indexBuffer);
            String key = node + "/" + layer.name();
            MeshChunk.Kind kind = layer == ChunkSectionLayer.TRANSLUCENT
                    ? MeshChunk.Kind.TERRAIN_TRANSLUCENT
                    : layer == ChunkSectionLayer.CUTOUT
                    ? MeshChunk.Kind.TERRAIN_FOLIAGE : MeshChunk.Kind.TERRAIN_OPAQUE;
            var indexType = draw.indexType() == com.mojang.renderpearl.api.pipeline.IndexType.INT
                    ? MeshChunk.IndexType.UNSIGNED_INT : MeshChunk.IndexType.UNSIGNED_SHORT;
            MinecraftCompiledScene.instance().publish(node, layer.name(),
                    new CompiledSceneMesh(key, kind,
                            SectionPos.sectionToBlockCoord(SectionPos.x(node)),
                            SectionPos.sectionToBlockCoord(SectionPos.y(node)),
                            SectionPos.sectionToBlockCoord(SectionPos.z(node)),
                            stride, attributes, vertices, indices,
                            indexType, draw.indexCount(), !layer.translucent()), compiled);
        } catch (RuntimeException | LinkageError e) {
            if (!captureFailureReported) {
                captureFailureReported = true;
                System.err.println(tr("[LuxLoader] Could not capture compiled section mesh: ") + e);
            }
        }
    }

    @Inject(method = "setSectionMesh", at = @At("RETURN"), require = 0)
    private void luxloader$acceptSection(SectionMesh accepted,
            CallbackInfoReturnable<SectionMesh> cir) {
        long node = ((SectionRenderDispatcher.RenderSection) (Object) this).getSectionNode();
        var state = dev.luxloader.mc.MinecraftResourceAccess.currentState();
        if (!state.ready() || accepted instanceof dev.luxloader.mc.hooks.SectionResourceGeneration tagged
                && tagged.luxloader$resourceGeneration() != state.generation()) {
            // A late compile may belong to a newly visible section absent from the reload barrier.
            // Keep host terrain until that section is rebuilt or removed as well.
            MinecraftCompiledScene.instance().captureIncomplete(node);
            return;
        }
        try {
            var expected = new java.util.HashSet<String>();
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                var draw = accepted.getSectionDraw(layer);
                if (draw != null && draw.indexCount() > 0) expected.add(layer.name());
            }
            if (!expected.isEmpty() && !(accepted instanceof dev.luxloader.mc.hooks.SectionResourceGeneration)) {
                MinecraftCompiledScene.instance().captureIncomplete(node);
                return;
            }
            MinecraftCompiledScene.instance().acceptSection(node, accepted, expected);
        } catch (RuntimeException | LinkageError failure) {
            MinecraftCompiledScene.instance().captureIncomplete(node);
            if (!captureFailureReported) {
                captureFailureReported = true;
                System.err.println(tr("[LuxLoader] Could not capture compiled section mesh: ") + failure);
            }
        }
    }

    @Inject(method = "reset", at = @At("HEAD"), require = 0)
    private void luxloader$evictSection(CallbackInfo ci) {
        long node = ((SectionRenderDispatcher.RenderSection) (Object) this).getSectionNode();
        MinecraftCompiledScene.instance().removeSection(node);
    }

    private static byte[] copy(ByteBuffer source) {
        ByteBuffer bytes = source.duplicate();
        byte[] result = new byte[bytes.remaining()];
        bytes.get(result);
        return result;
    }
}
