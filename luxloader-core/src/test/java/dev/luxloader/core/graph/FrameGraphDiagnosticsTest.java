package dev.luxloader.core.graph;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.gpu.GpuQueue;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.RenderPass;
import dev.luxloader.api.pipeline.StageKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests frame graph diagnostics that expose each node's resource accesses and dependencies when
 * scheduling differs from expectations.
 */
class FrameGraphDiagnosticsTest {

    /** Minimal pass stub. */
    private record ProbePass(GpuId id, Set<String> inputs, Set<String> outputs,
                             GpuQueue queue, boolean enabled) implements RenderPass {

        static ProbePass of(String name, String[] ins, String[] outs) {
            return new ProbePass(new GpuId("probe", name), Set.of(ins), Set.of(outs), null, true);
        }

        @Override
        public String name() {
            return id.path();
        }

        @Override
        public StageKind kind() {
            return StageKind.CUSTOM_POST;
        }

        @Override
        public dev.luxloader.api.pipeline.GpuResourceProvider resources() {
            return null;
        }

        @Override
        public dev.luxloader.api.pipeline.ResourceRequest initialize(
                dev.luxloader.api.pipeline.RenderContext ctx) {
            return dev.luxloader.api.pipeline.ResourceRequest.EMPTY;
        }

        @Override
        public void encode(FrameGraph graph, dev.luxloader.api.frame.FrameContext frame) {
        }
    }

    @Test
    @DisplayName("Dump Compilation")
    void dumpCompilation() {
        ProbePass writer = ProbePass.of("writer", new String[0], new String[] {"shared"});
        ProbePass readerA = ProbePass.of("reader-a", new String[] {"shared"}, new String[] {"out-a"});
        ProbePass readerB = ProbePass.of("reader-b", new String[] {"shared"}, new String[] {"out-b"});
        ProbePass disabled = new ProbePass(new GpuId("probe", "disabled"),
                Set.of(), Set.of("never"), null, false);

        // Shuffle declarations so correct scheduling cannot pass by coincidence.
        List<FrameGraphCompiler.Node> nodes = List.of(
                new FrameGraphCompiler.Node(readerB.id(), readerB, readerB.kind(), 0, 0, 0),
                new FrameGraphCompiler.Node(disabled.id(), disabled, disabled.kind(), 0, 1, 0),
                new FrameGraphCompiler.Node(readerA.id(), readerA, readerA.kind(), 0, 2, 0),
                new FrameGraphCompiler.Node(writer.id(), writer, writer.kind(), 0, 3, 0));

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(nodes);


        StringBuilder report = new StringBuilder("=== Frame graph compilation diagnostics ===");
        report.append("valid=").append(compiled.valid()).append(" error=").append(compiled.error()).append('\n');
        for (FrameGraphCompiler.Node n : nodes) {
            report.append(String.format("Node %-12s enabled=%-5s in=%-18s out=%-18s dependencies=%d%n",
                    n.nodeId().path(), n.isEnabled(), n.inputNames(), n.outputNames(), n.dependencyCount()));
        }
        report.append("Execution order: ")
                .append(compiled.ordered().stream().map(n -> n.nodeId().path()).toList())
                .append('\n');
        report.append("Warnings: ").append(compiled.warnings()).append('\n');
        System.out.println(report);

        assertTrue(compiled.valid(), compiled.error());
        List<String> order = compiled.ordered().stream().map(n -> n.nodeId().path()).toList();
        assertEquals(3, order.size(), "Disabled passes must be removed; actual order: " + order);
        assertFalse(order.contains("disabled"), "Disabled passes must not execute: " + order);
        assertEquals("writer", order.get(0), "The writer must execute first; actual order: " + order);
        assertTrue(order.containsAll(List.of("reader-a", "reader-b")), "Actual order: " + order);
    }

    @Test
    @DisplayName("Node Reflects Pass Declaration")
    void nodeReflectsPassDeclaration() {
        ProbePass pass = ProbePass.of("p", new String[] {"a", "b"}, new String[] {"c"});
        FrameGraphCompiler.Node node = new FrameGraphCompiler.Node(
                pass.id(), pass, pass.kind(), 0, 0, 0);

        assertTrue(node.isEnabled());
        assertEquals(Set.of("a", "b"), node.inputNames());
        assertEquals(Set.of("c"), node.outputNames());
        assertEquals(StageKind.CUSTOM_POST, node.kind());
        assertEquals(0, node.dependencyCount());

        node.enabled(false);
        assertFalse(node.isEnabled());
    }
}
