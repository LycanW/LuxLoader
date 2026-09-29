package dev.luxloader.core.graph;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.gpu.GpuQueue;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.RenderPass;
import dev.luxloader.api.pipeline.StageKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests frame graph scheduling contracts rather than an incidental topological order. Mutually
 * dependent resource accesses must be serialized consistently without imposing an arbitrary ordering
 * that the API does not require.
 */
class FrameGraphCompilerTest {

    /** Controllable pass stub. */
    private static final class StubPass implements RenderPass {
        private final GpuId id;
        private final Set<String> inputs = new LinkedHashSet<>();
        private final Set<String> outputs = new LinkedHashSet<>();
        private final List<RenderPass> deps = new ArrayList<>();
        private final GpuQueue queue;
        private final StageKind kind;
        private boolean enabled = true;

        StubPass(String name) {
            this(name, StageKind.CUSTOM_POST, null);
        }

        StubPass(String name, StageKind kind, GpuQueue queue) {
            this.id = new GpuId("test", name);
            this.kind = kind;
            this.queue = queue;
        }

        StubPass reads(String... names) {
            inputs.addAll(List.of(names));
            return this;
        }

        StubPass writes(String... names) {
            outputs.addAll(List.of(names));
            return this;
        }

        StubPass after(RenderPass other) {
            deps.add(other);
            return this;
        }

        StubPass disabled() {
            this.enabled = false;
            return this;
        }

        @Override
        public GpuId id() {
            return id;
        }

        @Override
        public String name() {
            return id.path();
        }

        @Override
        public StageKind kind() {
            return kind;
        }

        @Override
        public Set<String> inputs() {
            return Set.copyOf(inputs);
        }

        @Override
        public Set<String> outputs() {
            return Set.copyOf(outputs);
        }

        @Override
        public GpuQueue queue() {
            return queue;
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

        @Override
        public boolean enabled() {
            return enabled;
        }

        List<RenderPass> declaredDependencies() {
            return deps;
        }
    }

    /**
     * Construct a node. Resource access and explicit dependencies determine ordering; declaration order
     * only breaks otherwise unresolved ties.
     */
    private static FrameGraphCompiler.Node node(StubPass pass, int seq) {
        int family = pass.queue() == null ? 0 : pass.queue().familyIndex();
        return new FrameGraphCompiler.Node(pass.id(), pass, pass.kind(), 0, seq, family);
    }

    /** Supply the pass's explicit after dependencies to its node. */
    private static List<FrameGraphCompiler.Node> withDependencies(List<FrameGraphCompiler.Node> nodes) {
        var byPass = new java.util.LinkedHashMap<RenderPass, FrameGraphCompiler.Node>();
        for (FrameGraphCompiler.Node n : nodes) {
            byPass.putIfAbsent(n.pass(), n);
        }
        for (FrameGraphCompiler.Node n : nodes) {
            if (n.pass() instanceof StubPass stub) {
                for (RenderPass dep : stub.declaredDependencies()) {
                    FrameGraphCompiler.Node depNode = byPass.get(dep);
                    if (depNode != null) {
                        n.addDependency(depNode, "显式声明");
                    }
                }
            }
        }
        return nodes;
    }

    /** Pass paths in execution order. */
    private static List<String> order(FrameGraph.Compiled compiled) {
        return compiled.ordered().stream().map(n -> n.nodeId().path()).toList();
    }

    @Test
    void importedAttachmentsCanBeReadThenReplacedWithoutDependencyCycle() {
        StubPass trace = new StubPass("trace").reads(FrameGraph.COLOR, FrameGraph.DEPTH)
                .writes("rt.color", "rt.depth");
        StubPass resolve = new StubPass("resolve").reads("rt.color", "rt.depth")
                .writes(FrameGraph.COLOR, FrameGraph.DEPTH);
        StubPass transparent = new StubPass("transparent").reads(FrameGraph.COLOR, FrameGraph.DEPTH);
        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of(
                node(trace, 0), node(resolve, 1), node(transparent, 2)),
                Set.of(FrameGraph.COLOR, FrameGraph.DEPTH));
        assertTrue(compiled.valid(), compiled.error());
        assertEquals(List.of("trace", "resolve", "transparent"), order(compiled));
    }

    @Test
    @DisplayName("Reader After Writer")
    void readerAfterWriter() {
        StubPass producer = new StubPass("producer").writes("ssao");
        StubPass consumer = new StubPass("consumer").reads("ssao").writes("lit");

        // Declare the consumer first to verify ordering does not depend on declaration order.
        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(withDependencies(List.of(
                node(consumer, 0),
                node(producer, 1))));

        assertTrue(compiled.valid(), compiled.error());
        List<String> o = order(compiled);
        assertTrue(o.indexOf("producer") < o.indexOf("consumer"),
                "Writers must precede readers: " + o);
    }

    @Test
    @DisplayName("All Readers After Writer")
    void allReadersAfterWriter() {
        StubPass writer = new StubPass("writer").writes("shared");
        StubPass readerA = new StubPass("reader-a").reads("shared");
        StubPass readerB = new StubPass("reader-b").reads("shared");

        // Declare the reader first to exercise the two-pass dependency scan.
        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of(
                node(readerA, 0),
                node(readerB, 1),
                node(writer, 2)));

        assertTrue(compiled.valid(), compiled.error());
        List<String> o = order(compiled);
        assertEquals(3, o.size());
        assertTrue(o.indexOf("writer") < o.indexOf("reader-a"), "Actual: " + o);
        assertTrue(o.indexOf("writer") < o.indexOf("reader-b"), "Actual: " + o);
    }

    @Test
    @DisplayName("Explicit Dependency Wins")
    void explicitDependencyWins() {
        StubPass first = new StubPass("first");
        StubPass second = new StubPass("second").after(first);

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(withDependencies(List.of(
                node(second, 0),
                node(first, 1))));

        assertTrue(compiled.valid(), compiled.error());
        assertEquals(List.of("first", "second"), order(compiled));
    }

    @Test
    @DisplayName("Chained Explicit Dependencies")
    void chainedExplicitDependencies() {
        StubPass a = new StubPass("a");
        StubPass b = new StubPass("b").after(a);
        StubPass c = new StubPass("c").after(b);

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(withDependencies(List.of(
                node(c, 0),
                node(b, 1),
                node(a, 2))));

        assertTrue(compiled.valid(), compiled.error());
        assertEquals(List.of("a", "b", "c"), order(compiled));
    }

    @Test
    @DisplayName("Independent Nodes Keep Declaration Order")
    void independentNodesKeepDeclarationOrder() {
        List<FrameGraphCompiler.Node> nodes = List.of(
                node(new StubPass("a"), 0),
                node(new StubPass("b"), 1),
                node(new StubPass("c"), 2));

        assertEquals(List.of("a", "b", "c"), order(FrameGraphCompiler.compile(nodes)));
    }

    @Test
    @DisplayName("Compilation Is Deterministic")
    void compilationIsDeterministic() {
        StubPass writer = new StubPass("w").writes("x");
        StubPass reader1 = new StubPass("r1").reads("x").writes("y");
        StubPass reader2 = new StubPass("r2").reads("x").writes("z");
        StubPass sink = new StubPass("sink").reads("y", "z");

        List<FrameGraphCompiler.Node> nodes = List.of(
                node(reader2, 0),
                node(sink, 1),
                node(writer, 2),
                node(reader1, 3));

        String baseline = String.join(",", order(FrameGraphCompiler.compile(nodes)));
        for (int i = 0; i < 8; i++) {
            assertEquals(baseline, String.join(",", order(FrameGraphCompiler.compile(nodes))),
                    "Compilation " + (i + 2) + " produced a different order than the first compilation");
        }
    }

    @Test
    @DisplayName("Write After Write Is Serialized Deterministically")
    void writeAfterWriteIsSerializedDeterministically() {
        StubPass first = new StubPass("first").writes("shared");
        StubPass second = new StubPass("second").writes("shared");

        List<FrameGraphCompiler.Node> nodes = List.of(
                node(first, 0),
                node(second, 1));

        List<String> baseline = order(FrameGraphCompiler.compile(nodes));
        assertEquals(2, baseline.size());
        for (int i = 0; i < 6; i++) {
            assertEquals(baseline, order(FrameGraphCompiler.compile(nodes)),
                    "Write-after-write ordering must remain stable across compilations");
        }
    }

    @Test
    @DisplayName("Reading Produced Resource Does Not Warn")
    void readingProducedResourceDoesNotWarn() {
        StubPass writer = new StubPass("writer").writes("ssao");
        StubPass reader = new StubPass("reader").reads("ssao");

        // The reader is declared first, but a writer produces its resource; no warning is expected.
        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of(
                node(reader, 0),
                node(writer, 1)));

        assertTrue(compiled.valid(), compiled.error());
        assertTrue(compiled.warnings().isEmpty(), "Expected no warnings; actual: " + compiled.warnings());
    }

    @Test
    @DisplayName("Reading External Resource Warns Only")
    void readingExternalResourceWarnsOnly() {
        StubPass pass = new StubPass("uses-game-color").reads("color", "depth").writes("out");
        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(
                List.of(node(pass, 0)));

        assertTrue(compiled.valid(), compiled.error());
        assertEquals(1, compiled.ordered().size());
        assertEquals(2, compiled.warnings().size(), "One warning each for color and depth");
    }

    @Test
    @DisplayName("Detects Real Cycle")
    void detectsRealCycle() {
        // A reads B's r2 and writes r1; B reads A's r1 and writes r2. This is a genuine cycle.
        StubPass a = new StubPass("a").reads("r2").writes("r1");
        StubPass b = new StubPass("b").reads("r1").writes("r2");

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of(
                node(a, 0),
                node(b, 1)));

        assertFalse(compiled.valid(), "Mutual waits across resources must be detected");
        assertTrue(compiled.error().contains("循环依赖"), compiled.error());
        assertTrue(compiled.error().contains("a") && compiled.error().contains("b"),
                "Cycle diagnostics must list participating nodes: " + compiled.error());
    }

    @Test
    @DisplayName("Chained Rewrite Of One Resource Is Not ACycle")
    void chainedRewriteOfOneResourceIsNotACycle() {
        // A writes x; B reads x and writes y; C reads y and overwrites x. C produces the next version of x, so making B depend on every writer of x would create a false cycle.
        StubPass a = new StubPass("a").writes("x");
        StubPass b = new StubPass("b").reads("x").writes("y");
        StubPass c = new StubPass("c").reads("y").writes("x");

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of(
                node(a, 0),
                node(b, 1),
                node(c, 2)));

        assertTrue(compiled.valid(), () -> "Chained resource updates must not form a false cycle: " + compiled.error());
        assertEquals(List.of("a", "b", "c"), order(compiled),
                "Version order must be A writes x, B reads x and writes y, C reads y and overwrites x");
    }

    @Test
    @DisplayName("Reader Before All Writers Reads The First Version")
    void readerBeforeAllWritersReadsTheFirstVersion() {
        StubPass reader = new StubPass("reader").reads("bloom").writes("lit");
        StubPass first = new StubPass("first").writes("bloom");
        StubPass second = new StubPass("second").reads("bloom").writes("bloom");

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of(
                node(reader, 0),
                node(first, 1),
                node(second, 2)));

        assertTrue(compiled.valid(), compiled.error());
        List<String> o = order(compiled);
        assertEquals("first", o.get(0), "Writers must precede readers independently of declaration order: " + o);
        assertTrue(o.indexOf("reader") < o.indexOf("second"),
                "The next version must wait for readers of the previous version: " + o);
    }

    @Test
    @DisplayName("In Place Rewrites Are Ordered By Declaration")
    void inPlaceRewritesAreOrderedByDeclaration() {
        StubPass accumulate = new StubPass("accumulate").reads("history").writes("history");
        StubPass blur = new StubPass("blur").reads("history").writes("history");

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of(
                node(accumulate, 0),
                node(blur, 1)));

        assertTrue(compiled.valid(), () -> "In-place updates must not form a false cycle: " + compiled.error());
        assertEquals(List.of("accumulate", "blur"), order(compiled));
    }

    @Test
    @DisplayName("Detects Two Node Cycle")
    void detectsTwoNodeCycle() {
        StubPass x = new StubPass("x").reads("r").writes("r");
        StubPass y = new StubPass("y").reads("r").writes("r");

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of(
                node(x, 0),
                node(y, 1)));

        // Two read/write passes on the same resource can execute serially and must remain valid.
        assertTrue(compiled.valid(), "Read/write accesses to one resource can serialize without a cycle: " + compiled.error());
        assertEquals(2, order(compiled).size());
    }

    @Test
    @DisplayName("Detects Explicit Dependency Cycle")
    void detectsExplicitDependencyCycle() {
        StubPass a = new StubPass("a");
        StubPass b = new StubPass("b").after(a);
        a.after(b);

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(withDependencies(List.of(
                node(a, 0),
                node(b, 1))));

        assertFalse(compiled.valid());
        assertTrue(compiled.error().contains("循环依赖"), compiled.error());
    }

    @Test
    @DisplayName("Attachment Order Does Not Create False Cycle")
    void attachmentOrderDoesNotCreateFalseCycle() {
        // An upscale pass writes frame.final and a later sharpen or interpolation pass reads it. This normal producer-consumer relationship must not be reported as a cycle.
        StubPass upscale = new StubPass("upscale").writes("final");
        StubPass sharpen = new StubPass("sharpen").reads("final").writes("final2");

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of(
                node(upscale, 0),
                node(sharpen, 1)));

        assertTrue(compiled.valid(), "Valid dependencies across hooks must not form a cycle: " + compiled.error());
        assertTrue(order(compiled).indexOf("upscale") < order(compiled).indexOf("sharpen"));
    }

    @Test
    @DisplayName("Disabled Nodes Are Dropped")
    void disabledNodesAreDropped() {
        StubPass kept = new StubPass("kept").writes("x");
        StubPass removed = new StubPass("removed").reads("x").disabled();

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of(
                node(kept, 0),
                node(removed, 1)));

        assertTrue(compiled.valid(), compiled.error());
        assertEquals(List.of("kept"), order(compiled));
    }

    @Test
    @DisplayName("Node Inherits Enabled State")
    void nodeInheritsEnabledState() {
        StubPass on = new StubPass("on");
        StubPass off = new StubPass("off").disabled();

        assertTrue(node(on, 0).isEnabled());
        assertFalse(node(off, 1).isEnabled());
    }

    @Test
    @DisplayName("Groups By Queue")
    void groupsByQueue() {
        GpuQueue graphics = GpuQueue.of(0, 0, true, 1L, "graphics");
        GpuQueue compute = GpuQueue.of(1, 0, true, 2L, "compute");

        StubPass g1 = new StubPass("g1", StageKind.CUSTOM_POST, graphics).writes("a");
        StubPass c1 = new StubPass("c1", StageKind.SSAO, compute).reads("a").writes("b");
        StubPass g2 = new StubPass("g2", StageKind.TONEMAP, graphics).reads("b");

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(
                List.of(node(g1, 0),
                        node(c1, 1),
                        node(g2, 2)));

        assertTrue(compiled.valid(), compiled.error());
        assertEquals(3, compiled.queueGroups().size(), "Graphics, compute and graphics must form three batches");
        assertEquals(0, compiled.queueGroups().get(0).queueFamily());
        assertEquals(1, compiled.queueGroups().get(1).queueFamily());
        assertEquals(0, compiled.queueGroups().get(2).queueFamily());
    }

    @Test
    @DisplayName("Empty Graph Is Valid")
    void emptyGraphIsValid() {
        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of());
        assertTrue(compiled.valid());
        assertTrue(compiled.ordered().isEmpty());
        assertEquals(0, compiled.barriers());
    }

    @Test
    @DisplayName("Rejects Too Many Nodes")
    void rejectsTooManyNodes() {
        List<FrameGraphCompiler.Node> nodes = new ArrayList<>();
        for (int i = 0; i < FrameGraphCompiler.MAX_NODES + 1; i++) {
            nodes.add(node(new StubPass("p" + i), i));
        }
        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(nodes);
        assertFalse(compiled.valid());
        assertTrue(compiled.error().contains("超过上限"), compiled.error());
    }

    @Test
    @DisplayName("Frame Graph Impl Exposes Resources")
    void frameGraphImplExposesResources() {
        FrameGraphImpl graph = new FrameGraphImpl();
        graph.provide(FrameGraph.COLOR,
                dev.luxloader.api.gpu.ImageHandle.vkImage(0x1234L, "game-color"), "游戏场景颜色");
        graph.provide(FrameGraph.DEPTH,
                dev.luxloader.api.gpu.ImageHandle.vkImage(0x5678L, "game-depth"), "深度");

        assertTrue(graph.texture(FrameGraph.COLOR).isPresent());
        assertEquals(0x1234L, graph.texture(FrameGraph.COLOR).orElseThrow().bits());
        assertTrue(graph.texture("nonexistent").isEmpty());

        StubPass pass = new StubPass("writer").writes("my-output");
        graph.addPass(pass);
        assertTrue(graph.isProducedThisFrame("my-output"));
        assertTrue(graph.producedResources().contains("my-output"));
        assertFalse(graph.isProducedThisFrame("never-written"));

        graph.resolveDependencies();
        graph.setCompiled(FrameGraphCompiler.compile(graph.nodes()));
        String dump = graph.dump();
        assertTrue(dump.contains("color"), dump);
        assertTrue(dump.contains("游戏场景颜色"), dump);
        assertTrue(dump.contains("writer"), dump);
        assertTrue(dump.contains("本帧产出"), dump);
    }

    @Test
    @DisplayName("Disabled Pass Does Not Claim Output")
    void disabledPassDoesNotClaimOutput() {
        FrameGraphImpl graph = new FrameGraphImpl();
        graph.addPass(new StubPass("off").writes("ghost").disabled());
        assertFalse(graph.isProducedThisFrame("ghost"));
    }
}
