package dev.luxloader.mc.hooks.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.luxloader.api.pipeline.WorldFramePlan;
import dev.luxloader.mc.hooks.RenderHooks;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Composes selected passes while prepared chunks and feature draws are alive. */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Shadow @Final private LevelTargetBundle targets;
    @Shadow private void addSkyPass(FrameGraphBuilder graph, CameraRenderState camera,
                                    GpuBufferSlice slice) { throw new AssertionError(); }
    @Shadow private void addMainPass(FrameGraphBuilder graph,
                                     FeatureRenderDispatcher.PreparedFrame preparedFrame,
                                     GpuBufferSlice slice, ChunkSectionsToRender sections,
                                     boolean consistentDepthRequired) { throw new AssertionError(); }

    private WorldFramePlan luxloader$plan;
    @Shadow @Final private net.minecraft.client.renderer.state.level.LevelRenderState levelRenderState;
    @Shadow @Final private net.minecraft.client.renderer.entity.EntityRenderDispatcher entityRenderDispatcher;

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"), require = 1)
    private FeatureRenderDispatcher.PreparedFrame luxloader$prepareSecondaryEntities(
            FeatureRenderDispatcher dispatcher, net.minecraft.client.renderer.SubmitNodeStorage primary,
            Operation<FeatureRenderDispatcher.PreparedFrame> original) {
        var secondary = ((dev.luxloader.mc.hooks.SecondaryEntityStates) levelRenderState)
                .luxloader$secondaryEntities();
        var blocks = ((dev.luxloader.mc.hooks.SecondaryEntityStates) levelRenderState).luxloader$secondaryBlockEntities();
        if (RenderHooks.requiresDynamicGeometry() && (!secondary.isEmpty() || !blocks.isEmpty())) {
            var storage = new net.minecraft.client.renderer.SubmitNodeStorage();
            var hiddenStorage = new net.minecraft.client.renderer.SubmitNodeStorage();
            boolean hasVisible = !blocks.isEmpty(), hasHidden = false;
            var camera = levelRenderState.cameraRenderState;
            var pose = new com.mojang.blaze3d.vertex.PoseStack();
            for (var entry : secondary) {
                var entity = entry.state();
                entityRenderDispatcher.submit(entity, camera, entity.x - camera.pos.x(),
                        entity.y - camera.pos.y(), entity.z - camera.pos.z(), pose,
                        entry.cameraVisible() ? storage : hiddenStorage);
                hasVisible |= entry.cameraVisible();
                hasHidden |= !entry.cameraVisible();
            }
            var blockDispatcher = net.minecraft.client.Minecraft.getInstance().levelRenderer.blockEntityRenderDispatcher();
            for (var block : blocks) {
                pose.pushPose();
                pose.translate(block.blockPos.getX() - camera.pos.x(), block.blockPos.getY() - camera.pos.y(),
                        block.blockPos.getZ() - camera.pos.z());
                blockDispatcher.submit(block, pose, storage, camera);
                pose.popPose();
            }
            // prepareFrame leases the dispatcher's single reusable PreparedFrame.
            // Mesh capture has already copied its CPU data before this returns, and
            // no draw pass references the supplemental frame. Release it before
            // preparing the main view; a second prepare does not reset that lease.
            // Isolate the hidden camera body before batching by render type;
            // otherwise its visibility would be lost when meshes are merged.
            if (hasHidden) dev.luxloader.mc.hooks.PreparedMeshVisibility.prepare(false,
                    () -> original.call(dispatcher, hiddenStorage).close());
            if (hasVisible) original.call(dispatcher, storage).close();
        }
        secondary.clear();
        blocks.clear();
        return original.call(dispatcher, primary);
    }
    private CameraRenderState luxloader$skyCamera;
    private GpuBufferSlice luxloader$skySlice;
    private boolean luxloader$replaying;
    private boolean luxloader$skyDeferred;
    private WorldFramePlan.Step luxloader$recordingStep;
    private WorldFramePlan.Step luxloader$executingStep;
    @Unique
    private dev.luxloader.api.pipeline.CelestialRotation luxloader$skyRotation =
            dev.luxloader.api.pipeline.CelestialRotation.IDENTITY;

    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private void luxloader$beginDynamicScene(com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator,
            boolean outlines, CameraRenderState camera, GpuBufferSlice slice, org.joml.Vector4f fog,
            boolean first, boolean second, CallbackInfo ci) {
        dev.luxloader.mc.hooks.PreparedMeshCapture.begin(camera);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void luxloader$endDynamicScene(CallbackInfo ci) {
        dev.luxloader.mc.hooks.PreparedMeshCapture.end();
    }

    @Inject(method = "addSkyPass", at = @At("HEAD"), require = 0, cancellable = true)
    private void luxloader$deferSky(FrameGraphBuilder graph, CameraRenderState camera,
                                     GpuBufferSlice slice, CallbackInfo ci) {
        if (luxloader$replaying) return;
        luxloader$plan = RenderHooks.worldFramePlan();
        if (luxloader$plan == null) return;
        luxloader$skyDeferred = true;
        luxloader$skyCamera = camera;
        luxloader$skySlice = slice;
        ci.cancel();
    }

    @Inject(method = "addMainPass", at = @At("HEAD"), require = 0, cancellable = true)
    private void luxloader$composeWorld(FrameGraphBuilder graph,
                                            FeatureRenderDispatcher.PreparedFrame preparedFrame,
                                            GpuBufferSlice bufferSlice,
                                            ChunkSectionsToRender sections,
                                            boolean consistentDepthRequired,
                                            CallbackInfo ci) {
        if (luxloader$replaying) return;
        WorldFramePlan plan = luxloader$plan == null
                ? RenderHooks.worldFramePlan() : luxloader$plan;
        if (plan == null) {
            return;
        }
        luxloader$plan = null;
        try {
            luxloader$replaying = true;
            for (WorldFramePlan.Step step : plan.steps()) {
                luxloader$recordingStep = step;
                switch (step) {
                    case SKY -> {
                        if (luxloader$skyDeferred) {
                            luxloader$skyRotation = plan.celestialRotation();
                            addSkyPass(graph, luxloader$skyCamera, luxloader$skySlice);
                        }
                    }
                    case PREPARED_SCENE, PREPARED_FEATURES, PREPARED_OPAQUE_SCENE,
                         PREPARED_OPAQUE_FEATURES, PREPARED_TRANSPARENCY, PREPARED_TRANSPARENCY_FEATURES -> addMainPass(graph, preparedFrame, bufferSlice,
                            sections, consistentDepthRequired);
                    case PIPELINE -> {
                        FramePass pass = graph.addPass("luxloader-world-pipeline");
                        ResourceHandle<RenderTarget> target = pass.readsAndWrites(targets.main);
                        targets.main = target;
                        pass.disableCulling();
                        pass.executes(() -> RenderHooks.executeWorldPipeline(target.get()));
                    }
                }
            }
            ci.cancel();
        } finally {
            luxloader$replaying = false;
            luxloader$recordingStep = null;
            luxloader$skyCamera = null;
            luxloader$skySlice = null;
            luxloader$skyDeferred = false;
            luxloader$skyRotation = dev.luxloader.api.pipeline.CelestialRotation.IDENTITY;
        }
    }

    @WrapOperation(method = "addSkyPass", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/framegraph/FramePass;executes(Ljava/lang/Runnable;)V"), require = 1)
    private void luxloader$captureSkyOrientation(FramePass pass, Runnable draws, Operation<Void> original) {
        var rotation = luxloader$skyRotation;
        original.call(pass, (Runnable) () -> dev.luxloader.mc.hooks.PreparedSkyOrientation.draw(rotation, draws));
    }

    /** Capture the selection per scheduled pass, rather than sharing the last planned mode. */
    @WrapOperation(method = "addMainPass", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/framegraph/FramePass;executes(Ljava/lang/Runnable;)V"), require = 1)
    private void luxloader$captureDrawStage(FramePass pass, Runnable draws, Operation<Void> original) {
        WorldFramePlan.Step stage = luxloader$recordingStep;
        original.call(pass, (Runnable) () -> {
            WorldFramePlan.Step previous = luxloader$executingStep;
            luxloader$executingStep = stage;
            try {
                draws.run();
            } finally {
                luxloader$executingStep = previous;
            }
        });
    }

    @Inject(method = "executeSolid", at = @At("HEAD"), cancellable = true, require = 1)
    private void luxloader$selectSolidStage(CallbackInfo ci) {
        if (luxloader$executingStep == WorldFramePlan.Step.PREPARED_TRANSPARENCY
                || luxloader$executingStep == WorldFramePlan.Step.PREPARED_TRANSPARENCY_FEATURES) ci.cancel();
    }

    @WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"), require = 1)
    private void luxloader$selectTransparentTerrain(ChunkSectionsToRender sections, ChunkSectionLayerGroup group,
            RenderPass pass, GpuSampler sampler, GpuTextureView atlas, boolean wireframe, Operation<Void> original) {
        if (luxloader$executingStep != WorldFramePlan.Step.PREPARED_TRANSPARENCY_FEATURES)
            original.call(sections, group, pass, sampler, atlas, wireframe);
    }

    @WrapOperation(method = "executeOit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderOit(Lcom/mojang/renderpearl/api/textures/GpuSampler;Lnet/minecraft/client/renderer/oit/OitStage;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider$Parameters;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"), require = 1)
    private void luxloader$selectOitTerrain(ChunkSectionsToRender sections, GpuSampler sampler,
            net.minecraft.client.renderer.oit.OitStage stage,
            net.minecraft.client.renderer.oit.OitRenderPassProvider.Parameters parameters,
            GpuTextureView atlas, GpuTextureView lightmap, Operation<Void> original) {
        if (luxloader$executingStep != WorldFramePlan.Step.PREPARED_TRANSPARENCY_FEATURES)
            original.call(sections, sampler, stage, parameters, atlas, lightmap);
    }

    // Keep both vanilla transparency implementations, water masks and overlays intact.
    // They execute once, after the plugin's color/depth writes, in their own main pass.
    @Inject(method = {"prepareTranslucents", "executeClassicTransparency", "executeOit",
            "executeOutline", "executeSeeThrough", "executeAlwaysOnTop"},
            at = @At("HEAD"), cancellable = true, require = 1)
    private void luxloader$selectTransparentStage(CallbackInfo ci) {
        if (luxloader$executingStep != null && luxloader$executingStep.opaqueOnly()) ci.cancel();
    }

    /** Preserve Minecraft's feature submissions while the plugin owns opaque terrain. */
    @WrapOperation(method = "executeSolid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"),
            require = 1)
    private void luxloader$selectOpaqueTerrain(ChunkSectionsToRender sections,
            ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler,
            GpuTextureView atlas, boolean wireframe, Operation<Void> original) {
        if (luxloader$executingStep != WorldFramePlan.Step.PREPARED_FEATURES
                && luxloader$executingStep != WorldFramePlan.Step.PREPARED_OPAQUE_FEATURES) {
            original.call(sections, group, pass, sampler, atlas, wireframe);
        }
    }
}
