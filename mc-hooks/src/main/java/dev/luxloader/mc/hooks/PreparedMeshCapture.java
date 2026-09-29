package dev.luxloader.mc.hooks;

import com.mojang.blaze3d.vertex.MeshData;
import dev.luxloader.api.gpu.GpuFormat;
import dev.luxloader.api.gpu.ImageHandle;
import dev.luxloader.api.scene.*;
import dev.luxloader.mc.MinecraftDynamicScene;
import dev.luxloader.mc.MinecraftGraphicsAccess;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Captures final posed feature vertices before their CPU staging memory is freed. */
public final class PreparedMeshCapture {
    private record Binding(RenderType type, PreparedRenderType prepared, boolean cameraVisible) { }
    private static final Map<Object, Binding> bindings = new IdentityHashMap<>();
    private static final MinecraftGraphicsAccess graphics = MinecraftGraphicsAccess.create(
            PreparedMeshCapture.class.getClassLoader(), Map.of(), false);
    private static CameraRenderState camera;
    private static boolean warned;

    private PreparedMeshCapture() { }

    public static void begin(CameraRenderState state) {
        bindings.clear();
        MinecraftDynamicScene.beginFrame();
        camera = RenderHooks.requiresDynamicGeometry() ? state : null;
    }

    public static void end() { camera = null; bindings.clear(); }

    public static void bind(Object draw, RenderType type, PreparedRenderType prepared) {
        if (camera != null && !type.isOutline())
            bindings.put(draw, new Binding(type, prepared, PreparedMeshVisibility.cameraVisible()));
    }

    public static void append(Object draw, MeshData data) {
        Binding binding = bindings.get(draw);
        if (camera == null || camera.pos == null || binding == null || data == null) return;
        try {
            var state = data.drawState();
            var topology = state.primitiveTopology();
            if (topology != com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS
                    && topology != com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES) return;
            var format = state.format();
            var position = format.getElements().stream().filter(e -> e.name().equals("Position")).findFirst();
            if (position.isEmpty() || format.getElements().stream().noneMatch(e -> e.name().equals("UV0"))) return;
            var texture = binding.prepared().textures().stream()
                    .filter(t -> t.name().equals("Sampler0")).findFirst().orElse(null);
            if (texture == null) return;
            var view = texture.textureView();
            long image = graphics.vkImageOf(view).orElse(0);
            if (image == 0) throw new IllegalStateException("Feature albedo image unavailable");
            GpuFormat imageFormat = GpuFormat.fromVk(
                    com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(view.texture().getFormat()));
            SceneImage albedo = new SceneImage("feature-albedo-" + image,
                    ImageHandle.vkImage(image, "feature-albedo"), imageFormat,
                    view.getWidth(0), view.getHeight(0));
            byte[] bytes = new byte[data.vertexBuffer().remaining()];
            data.vertexBuffer().duplicate().get(bytes);
            var vertices = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int stride = format.getVertexSize(), offset = position.get().offset();
            int ox = (int)Math.floor(camera.pos.x()), oy = (int)Math.floor(camera.pos.y());
            int oz = (int)Math.floor(camera.pos.z());
            for (int i = 0; i < state.vertexCount(); i++) {
                int p = i * stride + offset;
                vertices.putFloat(p, vertices.getFloat(p) + (float)(camera.pos.x() - ox));
                vertices.putFloat(p + 4, vertices.getFloat(p + 4) + (float)(camera.pos.y() - oy));
                vertices.putFloat(p + 8, vertices.getFloat(p + 8) + (float)(camera.pos.z() - oz));
            }
            List<CompiledSceneMesh.Attribute> attributes = format.getElements().stream().map(e ->
                    new CompiledSceneMesh.Attribute(e.name(), e.offset(), e.format().name())).toList();
            String cutoff = binding.type().pipeline().getShaderDefines().values().get("ALPHA_CUTOUT");
            float alphaCutoff = cutoff == null ? 0f : Float.parseFloat(cutoff);
            DynamicSceneMesh.Blend blend = DynamicSceneMesh.Blend.OPAQUE;
            if (binding.type().hasBlending()) {
                var function = binding.type().pipeline().getColorTargetStates().getFirst().blendFunction().orElse(null);
                if (com.mojang.renderpearl.api.pipeline.BlendFunction.TRANSLUCENT.equals(function)) {
                    blend = DynamicSceneMesh.Blend.ALPHA;
                } else if (com.mojang.renderpearl.api.pipeline.BlendFunction.ADDITIVE.equals(function)
                        || com.mojang.renderpearl.api.pipeline.BlendFunction.LIGHTNING.equals(function)) {
                    blend = DynamicSceneMesh.Blend.ADDITIVE;
                } else return; // Screen overlays/glint require an explicit contributor, not opaque geometry.
                alphaCutoff = Math.max(alphaCutoff, 1f / 255);
            }
            ByteBuffer sourceIndices = data.indexBuffer();
            byte[] indexBytes = new byte[sourceIndices == null ? 0 : sourceIndices.remaining()];
            if (sourceIndices != null) sourceIndices.duplicate().get(indexBytes);
            var mesh = new CompiledSceneMesh("feature/" + bindings.size() + "/" + System.identityHashCode(data),
                    MeshChunk.Kind.ENTITY, ox, oy, oz, stride, attributes, bytes, indexBytes,
                    state.indexType() == com.mojang.renderpearl.api.pipeline.IndexType.INT
                            ? MeshChunk.IndexType.UNSIGNED_INT : MeshChunk.IndexType.UNSIGNED_SHORT, state.indexCount(),
                    topology == com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS
                            ? CompiledSceneMesh.Topology.QUADS : CompiledSceneMesh.Topology.TRIANGLES, true);
            MinecraftDynamicScene.publish(new DynamicSceneMesh(mesh, albedo, alphaCutoff, blend, binding.cameraVisible()));
        } catch (RuntimeException | LinkageError failure) {
            if (!warned) { warned = true; System.err.println("[LuxLoader] Prepared mesh capture: " + failure); }
        }
    }
}
