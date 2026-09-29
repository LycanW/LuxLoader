package dev.luxloader.mc.hooks;

import java.util.List;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Extraction-thread output transported with the owning frame, never via a global world list. */
public interface SecondaryEntityStates {
    record Entry(EntityRenderState state, boolean cameraVisible) { }
    List<Entry> luxloader$secondaryEntities();
    List<net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> luxloader$secondaryBlockEntities();
}
