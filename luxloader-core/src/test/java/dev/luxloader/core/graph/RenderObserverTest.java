package dev.luxloader.core.graph;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.debug.RenderObserver;
import dev.luxloader.api.debug.RenderObservers;
import dev.luxloader.api.frame.FrameContext;
import dev.luxloader.api.frame.FrameTiming;
import dev.luxloader.api.gpu.GpuCommands;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.gpu.GpuQueue;
import dev.luxloader.api.gpu.ImageDesc;
import dev.luxloader.api.gpu.ImageHandle;
import dev.luxloader.api.gpu.GpuFormat;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.GpuResourceProvider;
import dev.luxloader.api.pipeline.RenderPass;
import dev.luxloader.api.pipeline.ResourceRequest;
import dev.luxloader.api.pipeline.StageKind;
import dev.luxloader.api.scene.SceneSnapshot;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderObserverTest {
    @Test
    void passEventsExposeAccessAndPublishedImageFacts() throws Exception {
        ImageDesc imageDesc = ImageDesc.renderTarget("observed", 4, 3, GpuFormat.R8G8B8A8_UNORM);
        GpuResourceProvider resources = (GpuResourceProvider) Proxy.newProxyInstance(
                GpuResourceProvider.class.getClassLoader(), new Class<?>[] {GpuResourceProvider.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("imageInfo")) {
                        return Optional.of(new GpuResourceProvider.ImageInfo(imageDesc,
                                GpuResourceProvider.Ownership.OWNED, "test-pass", 5L));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ImageHandle input = ImageHandle.vkImage(101L, "input");
        ImageHandle output = ImageHandle.vkImage(102L, "output");

        FrameGraphImpl graph = new FrameGraphImpl();
        graph.useResourceProvider(resources);
        graph.provide("input", input, "input image");
        graph.addPass(new RenderPass() {
            @Override public GpuId id() { return new GpuId("test", "observed-pass"); }
            @Override public String name() { return "Observed pass"; }
            @Override public StageKind kind() { return StageKind.CUSTOM_POST; }
            @Override public Set<String> inputs() { return Set.of("input"); }
            @Override public Set<String> outputs() { return Set.of("output"); }
            @Override public GpuResourceProvider resources() { return resources; }
            @Override public ResourceRequest initialize(dev.luxloader.api.pipeline.RenderContext ctx) {
                return ResourceRequest.EMPTY;
            }
            @Override public void encode(FrameGraph target, FrameContext frame) {
                target.publish("output", output, 17L);
            }
        });
        graph.resolveDependencies();
        graph.setCompiled(FrameGraphCompiler.compile(graph.nodes(), Set.of("input")));
        assertTrue(graph.compiled().valid(), graph.compiled().error());

        GpuCommands commands = (GpuCommands) Proxy.newProxyInstance(GpuCommands.class.getClassLoader(),
                new Class<?>[] {GpuCommands.class}, (proxy, method, args) -> {
                    if (method.getName().equals("imageLayout")) return "SHADER_READ_ONLY_OPTIMAL";
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        GpuDevice device = (GpuDevice) Proxy.newProxyInstance(GpuDevice.class.getClassLoader(),
                new Class<?>[] {GpuDevice.class}, (proxy, method, args) -> null);
        FrameContext frame = new FrameContext(null, null, FrameTiming.unknown(42L), device, commands,
                GpuQueue.of(0, 0, false, 1L, "graphics"), false, false, true, false,
                SceneSnapshot.empty(42L));
        List<RenderObserver.PassEvent> events = new ArrayList<>();
        try (AutoCloseable registration = RenderObservers.register(events::add)) {
            FrameGraphExecution.Outcome outcome = FrameGraphExecution.run(graph, graph.compiled(), frame, null);
            assertEquals(1, outcome.executed());
        }

        assertEquals(List.of(RenderObserver.Phase.BEFORE_ENCODE, RenderObserver.Phase.AFTER_ENCODE),
                events.stream().map(RenderObserver.PassEvent::phase).toList());
        RenderObserver.PassEvent before = events.get(0);
        RenderObserver.PassEvent after = events.get(1);
        assertEquals(42L, before.frameId());
        assertEquals("test:observed-pass", before.passId());
        assertEquals(RenderObserver.AccessKind.READ, before.resources().get("input").access());
        assertEquals(RenderObserver.AccessKind.WRITE, after.resources().get("output").access());
        assertEquals("SHADER_READ_ONLY_OPTIMAL", after.resources().get("output").currentLayout());
        FrameGraph.ResourceInfo outputInfo = after.resources().get("output").description().orElseThrow();
        assertEquals(17L, outputInfo.contentVersion());
        assertEquals("test-pass", outputInfo.ownerId());
        assertEquals(GpuResourceProvider.Ownership.OWNED, outputInfo.ownership());
        assertEquals(imageDesc, outputInfo.image());
    }
}
