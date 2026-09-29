package dev.luxloader.core.graph;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.PipelineDescriptor;
import dev.luxloader.api.pipeline.RenderPass;
import dev.luxloader.api.pipeline.RenderPipeline;
import dev.luxloader.api.pipeline.StageKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests frame boundaries and explicit ordering. frame.begin, frame.end and frame.final represent
 * scheduling constraints that the compiler must enforce independently of declaration order.
 */
class FrameGraphBoundaryTest {

    /** Controllable pass stub describing resource access and enablement without GPU work. */
    private static final class Pass implements RenderPass {
        private final GpuId id;
        private final Set<String> inputs = new LinkedHashSet<>();
        private final Set<String> outputs = new LinkedHashSet<>();
        private boolean enabled = true;

        Pass(String name) {
            this.id = new GpuId("test", name);
        }

        Pass reads(String... names) {
            inputs.addAll(List.of(names));
            return this;
        }

        Pass writes(String... names) {
            outputs.addAll(List.of(names));
            return this;
        }

        Pass disabled() {
            enabled = false;
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
            return StageKind.CUSTOM_POST;
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
        public boolean enabled() {
            return enabled;
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

    /**
     * Use the real PassHost base class so dependsOn declarations pass through its stored dependency list
     * and the compiler's resolution path.
     */
    private static final class HostedPass extends dev.luxloader.api.pipeline.PassHost {
        HostedPass(String name, RenderPipeline owner) {
            super(new GpuId("test", name), name, StageKind.CUSTOM_POST, owner);
        }

        HostedPass declaring(String... names) {
            reads(names);
            return this;
        }

        @Override
        protected dev.luxloader.api.pipeline.ResourceRequest doInitialize(
                dev.luxloader.api.pipeline.RenderContext ctx) {
            return dev.luxloader.api.pipeline.ResourceRequest.EMPTY;
        }

        @Override
        protected void doEncode(FrameGraph graph,
                               dev.luxloader.api.frame.FrameContext frame) {
        }
    }

    /** Minimal pipeline satisfying the PassHost constructor. */
    private static final class StubOwner implements RenderPipeline {
        @Override
        public PipelineDescriptor descriptor() {
            return PipelineDescriptor.builder(new GpuId("test", "owner"), "测试管线", "1.0.0").build();
        }

        @Override
        public void initialize(dev.luxloader.api.pipeline.RenderContext ctx) {
        }

        @Override
        public List<RenderPass> passes() {
            return List.of();
        }

        @Override
        public void encodeFrame(FrameGraph graph,
                                dev.luxloader.api.frame.FrameContext frame) {
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("Pass Host Depends On Is Honoured")
    void passHostDependsOnIsHonoured() {
        RenderPipeline owner = new StubOwner();
        HostedPass first = new HostedPass("first", owner);
        HostedPass second = new HostedPass("second", owner);
        second.dependsOn(first);

        // Attach the dependent pass first; declarations must determine execution order.
        FrameGraph.Compiled compiled = build(second, first);

        assertTrue(compiled.valid(), compiled.error());
        assertEquals(List.of("first", "second"), order(compiled));
    }

    /**
     * Attach passes through FrameGraphImpl and compile them so before() and dependsOn use the actual
     * dependency resolution path.
     */
    private static FrameGraph.Compiled build(RenderPass... passes) {
        FrameGraphImpl graph = new FrameGraphImpl();
        for (RenderPass p : passes) {
            graph.addPass(p);
        }
        graph.resolveDependencies();
        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(graph.nodes());
        graph.setCompiled(compiled);
        return compiled;
    }

    private static List<String> order(FrameGraph.Compiled compiled) {
        return compiled.ordered().stream().map(n -> n.nodeId().path()).toList();
    }

    private static int indexOf(FrameGraph.Compiled compiled, String name) {
        return order(compiled).indexOf(name);
    }

    // ------------------------------------------------------------------
    // frame.begin / frame.end
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Reader Of Begin Goes First")
    void readerOfBeginGoesFirst() {
        Pass scene = new Pass("scene").writes("my.color");
        Pass lighting = new Pass("lighting").reads("my.color").writes(FrameGraph.FINAL);
        // Declare this pass last; its frame-begin read must still determine scheduling.
        Pass setup = new Pass("setup").reads(FrameGraph.FRAME_BEGIN).writes("my.constants");

        FrameGraph.Compiled compiled = build(scene, lighting, setup);

        assertTrue(compiled.valid(), compiled.error());
        assertEquals("setup", compiled.first().orElseThrow().nodeId().path(),
                "A frame-begin reader must execute first; actual order: " + order(compiled));
        assertTrue(indexOf(compiled, "setup") < indexOf(compiled, "scene"));
        assertTrue(indexOf(compiled, "setup") < indexOf(compiled, "lighting"));
    }

    @Test
    @DisplayName("Writer Of End Goes Last")
    void writerOfEndGoesLast() {
        Pass teardown = new Pass("teardown").writes(FrameGraph.FRAME_END);
        Pass scene = new Pass("scene").writes("my.color");
        Pass present = new Pass("present").reads("my.color").writes(FrameGraph.FINAL);

        FrameGraph.Compiled compiled = build(teardown, scene, present);

        assertTrue(compiled.valid(), compiled.error());
        assertEquals("teardown", compiled.last().orElseThrow().nodeId().path(),
                "A frame-end writer must execute last; actual order: " + order(compiled));
        assertTrue(indexOf(compiled, "present") < indexOf(compiled, "teardown"));
    }

    @Test
    @DisplayName("Begin And End Bracket The Frame")
    void beginAndEndBracketTheFrame() {
        Pass setup = new Pass("setup").reads(FrameGraph.FRAME_BEGIN);
        Pass a = new Pass("a").writes("my.a");
        Pass b = new Pass("b").reads("my.a").writes("my.b");
        Pass teardown = new Pass("teardown").writes(FrameGraph.FRAME_END);

        FrameGraph.Compiled compiled = build(b, teardown, a, setup);

        assertTrue(compiled.valid(), compiled.error());
        assertEquals(List.of("setup", "a", "b", "teardown"), order(compiled));
    }

    @Test
    @DisplayName("Multiple Begin Readers Keep Declaration Order")
    void multipleBeginReadersKeepDeclarationOrder() {
        Pass first = new Pass("first").reads(FrameGraph.FRAME_BEGIN);
        Pass second = new Pass("second").reads(FrameGraph.FRAME_BEGIN);
        Pass work = new Pass("work").writes("my.x");

        FrameGraph.Compiled compiled = build(first, second, work);

        assertTrue(compiled.valid(), compiled.error());
        List<String> o = order(compiled);
        assertEquals(0, Math.min(o.indexOf("first"), o.indexOf("second")),
                "The two frame-begin readers must occupy the first two positions: " + o);
        assertTrue(o.indexOf("work") == 2, "Ordinary passes must follow frame-begin readers: " + o);
        assertTrue(compiled.warnings().stream().anyMatch(w -> w.contains(FrameGraph.FRAME_BEGIN)),
                "Multiple frame-begin readers must produce an ambiguity warning: " + compiled.warnings());
    }

    @Test
    @DisplayName("Conflicting Boundary Is Rejected")
    void conflictingBoundaryIsRejected() {
        Pass both = new Pass("both").reads(FrameGraph.FRAME_BEGIN).writes(FrameGraph.FRAME_END);
        Pass work = new Pass("work").writes("my.x");

        FrameGraph.Compiled compiled = build(both, work);

        assertFalse(compiled.valid(),
                "A pass cannot be both first and last in a multi-pass graph");
        assertTrue(compiled.error().contains("both"), compiled.error());
        assertTrue(compiled.error().contains("拆成两个阶段"), compiled.error());
    }

    @Test
    @DisplayName("Single Node May Own Both Boundaries")
    void singleNodeMayOwnBothBoundaries() {
        Pass only = new Pass("only").reads(FrameGraph.FRAME_BEGIN).writes(FrameGraph.FRAME_END);

        FrameGraph.Compiled compiled = build(only);

        assertTrue(compiled.valid(), compiled.error());
        assertEquals(List.of("only"), order(compiled));
    }

    @Test
    @DisplayName("Disabled Boundary Node Is Ignored")
    void disabledBoundaryNodeIsIgnored() {
        Pass setup = new Pass("setup").reads(FrameGraph.FRAME_BEGIN).disabled();
        Pass work = new Pass("work").writes("my.x");

        FrameGraph.Compiled compiled = build(setup, work);

        assertTrue(compiled.valid(), compiled.error());
        assertEquals(List.of("work"), order(compiled));
    }

    // Warning diagnostics.

    @Test
    @DisplayName("Host Frame Resources Do Not Warn")
    void hostFrameResourcesDoNotWarn() {
        Pass upscale = new Pass("upscale")
                .reads(FrameGraph.COLOR, FrameGraph.DEPTH, FrameGraph.MOTION,
                        FrameGraph.EXPOSURE, FrameGraph.UI, FrameGraph.SWAPCHAIN,
                        FrameGraph.HISTORY, FrameGraph.FRAME_BEGIN)
                .writes(FrameGraph.FINAL);

        FrameGraph.Compiled compiled = build(upscale);

        assertTrue(compiled.valid(), compiled.error());
        assertTrue(compiled.warnings().isEmpty(),
                "Valid reads of host resources must not produce warnings: "
                        + compiled.warnings());
    }

    @Test
    @DisplayName("Misspelled Resource Still Warns")
    void misspelledResourceStillWarns() {
        Pass broken = new Pass("broken").reads("my.clor").writes("my.x");

        FrameGraph.Compiled compiled = build(broken);

        assertTrue(compiled.valid(), compiled.error());
        assertTrue(compiled.warnings().stream().anyMatch(w -> w.contains("my.clor")),
                "Unknown resource names must be reported: " + compiled.warnings());
    }

    // Final output and explicit ordering.

    @Test
    @DisplayName("Final Producer Is The Last Writer")
    void finalProducerIsTheLastWriter() {
        Pass a = new Pass("a").writes(FrameGraph.FINAL);
        Pass b = new Pass("b").reads(FrameGraph.FINAL).writes("my.tmp");
        Pass c = new Pass("c").reads("my.tmp").writes(FrameGraph.FINAL);

        FrameGraph.Compiled compiled = build(a, b, c);

        assertTrue(compiled.valid(), compiled.error());
        assertEquals("c", compiled.finalProducer().orElseThrow().nodeId().path(),
                "Write-after-write ordering must preserve declaration order so the final writer is presented");
        assertEquals("a", compiled.first().orElseThrow().nodeId().path());
        assertEquals("c", compiled.last().orElseThrow().nodeId().path());
    }

    @Test
    @DisplayName("Final Producer Is Empty When Nobody Claims It")
    void finalProducerIsEmptyWhenNobodyClaimsIt() {
        FrameGraph.Compiled compiled = build(new Pass("a").writes("my.x"));

        assertTrue(compiled.finalProducer().isEmpty());
    }

    @Test
    @DisplayName("Empty Graph Has No Boundaries")
    void emptyGraphHasNoBoundaries() {
        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(List.of());

        assertTrue(compiled.valid());
        assertTrue(compiled.first().isEmpty());
        assertTrue(compiled.last().isEmpty());
        assertTrue(compiled.finalProducer().isEmpty());
        assertEquals("", compiled.sequence());
    }

    @Test
    @DisplayName("Sequence Renders Readable Path")
    void sequenceRendersReadablePath() {
        Pass a = new Pass("a").writes("my.a");
        Pass b = new Pass("b").reads("my.a").writes("my.b");

        FrameGraph.Compiled compiled = build(a, b);

        assertEquals("a → b", compiled.sequence());
    }

    @Test
    @DisplayName("Before Is Not ANo Op")
    void beforeIsNotANoOp() {
        Pass earlier = new Pass("earlier");
        Pass later = new Pass("later");

        FrameGraphImpl graph = new FrameGraphImpl();
        // Attach later before earlier, then explicitly order earlier before later.
        FrameGraph.PassNode laterNode = graph.addPass(later);
        FrameGraph.PassNode earlierNode = graph.addPass(earlier);
        earlierNode.before(later);
        graph.resolveDependencies();

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(graph.nodes());
        graph.setCompiled(compiled);

        assertTrue(compiled.valid(), compiled.error());
        assertEquals(List.of("earlier", "later"), order(compiled),
                "before() must order the target accordingly: " + order(compiled));
        assertNotNull(laterNode);
    }

    @Test
    @DisplayName("Before Unknown Pass Is Harmless")
    void beforeUnknownPassIsHarmless() {
        Pass a = new Pass("a");
        Pass notAdded = new Pass("not-added");

        FrameGraphImpl graph = new FrameGraphImpl();
        FrameGraph.PassNode node = graph.addPass(a);
        node.before(notAdded);
        node.after(notAdded);
        graph.resolveDependencies();

        FrameGraph.Compiled compiled = FrameGraphCompiler.compile(graph.nodes());

        assertTrue(compiled.valid(), compiled.error());
        assertEquals(List.of("a"), order(compiled));
    }
}
