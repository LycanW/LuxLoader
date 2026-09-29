package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.mc.hooks.RenderHooks;
import dev.luxloader.mc.hooks.SecondaryEntityStates;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Secondary rays need distance-limited entities outside the main camera frustum, including self. */
@Mixin(LevelExtractor.class)
public class SecondaryEntityExtractionMixin {
    @Unique private final Set<Integer> luxloader$extracted = new HashSet<>();

    @Inject(method = "extractVisibleBlockEntities", at = @At("RETURN"), require = 1)
    private void luxloader$secondaryBlocks(Camera camera, float partialTick, LevelRenderState state, CallbackInfo ci) {
        var secondary = ((SecondaryEntityStates) state).luxloader$secondaryBlockEntities();
        secondary.clear();
        if (!RenderHooks.requiresDynamicGeometry()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var positions = new HashSet<net.minecraft.core.BlockPos>();
        for (var visible : state.blockEntityRenderStates) positions.add(visible.blockPos);
        var chunks = new HashSet<Long>();
        for (var mesh : dev.luxloader.mc.MinecraftCompiledScene.instance().snapshot().meshes()) {
            int x = mesh.originX() >> 4, z = mesh.originZ() >> 4;
            if (!chunks.add(((long)x << 32) | (z & 0xffffffffL))) continue;
            var chunk = mc.level.getChunkSource().getChunk(x, z,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            if (chunk == null) continue;
            for (var entity : chunk.getBlockEntities().values()) {
                if (entity.isRemoved() || !positions.add(entity.getBlockPos())) continue;
                var extracted = mc.levelRenderer.blockEntityRenderDispatcher()
                        .tryExtractRenderState(entity, partialTick, null, false);
                if (extracted != null) secondary.add(extracted);
            }
        }
    }

    @Inject(method = "extractVisibleEntities", at = @At("HEAD"), require = 1)
    private void luxloader$beginEntities(Camera camera, Frustum frustum, DeltaTracker delta,
            LevelRenderState state, CallbackInfo ci) {
        luxloader$extracted.clear();
        ((SecondaryEntityStates) state).luxloader$secondaryEntities().clear();
    }

    @Inject(method = "extractEntity", at = @At("RETURN"), require = 1)
    private void luxloader$rememberEntity(Entity entity, float partialTick,
            CallbackInfoReturnable<EntityRenderState> cir) {
        luxloader$extracted.add(entity.getId());
    }

    @Inject(method = "extractVisibleEntities", at = @At("RETURN"), require = 1)
    private void luxloader$extractSecondary(Camera camera, Frustum frustum, DeltaTracker delta,
            LevelRenderState state, CallbackInfo ci) {
        if (!RenderHooks.requiresDynamicGeometry()) return;
        Minecraft minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) return;
        var position = camera.position();
        var secondary = ((SecondaryEntityStates) state).luxloader$secondaryEntities();
        var dispatcher = minecraft.levelRenderer.entityRenderDispatcher();
        for (Entity entity : level.entitiesForRendering()) {
            if (luxloader$extracted.contains(entity.getId()) || entity.isRemoved()
                    || !entity.shouldRender(position.x(), position.y(), position.z())) continue;
            float tick = delta.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
            boolean cameraVisible = !state.cameraRenderState.isFirstPerson || entity != camera.entity();
            secondary.add(new SecondaryEntityStates.Entry(dispatcher.extractEntity(entity, tick), cameraVisible));
        }
    }
}
