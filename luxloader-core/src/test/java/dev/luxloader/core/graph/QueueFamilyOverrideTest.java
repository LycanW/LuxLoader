package dev.luxloader.core.graph;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.frame.FrameContext;
import dev.luxloader.api.gpu.GpuQueue;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.GpuResourceProvider;
import dev.luxloader.api.pipeline.RenderContext;
import dev.luxloader.api.pipeline.RenderPass;
import dev.luxloader.api.pipeline.ResourceRequest;
import dev.luxloader.api.pipeline.StageKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Passes accessing exclusive host resources must use the graphics queue family. Minecraft 26.3 creates
 * those resources with exclusive sharing and does not transfer ownership to our compute family;
 * overriding the requested queue prevents invalid cross-family access.
 */
class QueueFamilyOverrideTest {

    private static final GpuQueue COMPUTE = GpuQueue.of(1, 0, true, 2L, "compute");

    /** Minimal pass stub exposing only ID, inputs and queue selection. */
    private record Pass(String name, Set<String> inputs, GpuQueue queue) implements RenderPass {

        @Override
        public GpuId id() {
            return new GpuId("t", name);
        }

        @Override
        public StageKind kind() {
            return StageKind.CUSTOM_POST;
        }

        @Override
        public Set<String> inputs() {
            return inputs;
        }

        @Override
        public Set<String> outputs() {
            return Set.of();
        }

        @Override
        public GpuQueue queue() {
            return queue;
        }

        @Override
        public GpuResourceProvider resources() {
            return null;
        }

        @Override
        public ResourceRequest initialize(RenderContext ctx) {
            return ResourceRequest.EMPTY;
        }

        @Override
        public void encode(FrameGraph graph, FrameContext frame) {
        }
    }

    @Test
    @DisplayName("Keeps Declared Family When No Host Resource Is Read")
    void keepsDeclaredFamilyWhenNoHostResourceIsRead() {
        FrameGraphImpl graph = new FrameGraphImpl();
        graph.markForeign(FrameGraph.COLOR);

        graph.addPass(new Pass("own-only", Set.of("pipeline/mine"), COMPUTE));

        assertEquals(1, graph.nodes().get(0).queueFamily(),
                "Passes without host-resource access must retain their requested queue family");
        assertTrue(graph.queueFamilyOverridden().isEmpty(),
                "Unchanged queue assignments must produce no override record");
    }

    @Test
    @DisplayName("Forces Graphics Family When Host Resource Is Read")
    void forcesGraphicsFamilyWhenHostResourceIsRead() {
        FrameGraphImpl graph = new FrameGraphImpl();
        graph.markForeign(FrameGraph.COLOR);

        graph.addPass(new Pass("reads-host", Set.of(FrameGraph.COLOR), COMPUTE));

        assertEquals(0, graph.nodes().get(0).queueFamily(),
                "Exclusive host resources require the graphics queue family");
        assertEquals(1, graph.queueFamilyOverridden().size(), "Queue overrides must be recorded");
        assertTrue(graph.queueFamilyOverridden().get(0).contains(FrameGraph.COLOR),
                "The override must identify the responsible host resource: "
                        + graph.queueFamilyOverridden().get(0));
    }

    @Test
    @DisplayName("Loader Owned History Is Not Treated As Foreign")
    void loaderOwnedHistoryIsNotTreatedAsForeign() {
        FrameGraphImpl graph = new FrameGraphImpl();
        // Mark only host images as external; deliberately exclude HISTORY.
        graph.markForeign(FrameGraph.COLOR);

        graph.addPass(new Pass("reads-history", Set.of(FrameGraph.HISTORY), COMPUTE));

        assertEquals(1, graph.nodes().get(0).queueFamily(),
                "Concurrently shared history resources must remain usable across queue families");
    }

    @Test
    void hostFrameKeepsAllPassesInMinecraftGraphicsSubmission() {
        FrameGraphImpl graph = new FrameGraphImpl(3, true);
        graph.addPass(new Pass("own-only", Set.of("pipeline/mine"), COMPUTE));
        graph.addPass(new Pass("default", Set.of(), null));

        assertEquals(3, graph.nodes().get(0).queueFamily());
        assertEquals(3, graph.nodes().get(1).queueFamily());
    }

    @Test
    void detachedFrameUsesActualGraphicsFamilyForForeignResources() {
        FrameGraphImpl graph = new FrameGraphImpl(3, false);
        graph.markForeign(FrameGraph.COLOR);
        graph.addPass(new Pass("reads-host", Set.of(FrameGraph.COLOR), COMPUTE));

        assertEquals(3, graph.nodes().get(0).queueFamily());
    }
}
