package dev.luxloader.mc.hooks;

import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public interface SectionEmission {
    Map<ChunkSectionLayer, float[]> luxloader$emissionLayers();
}
