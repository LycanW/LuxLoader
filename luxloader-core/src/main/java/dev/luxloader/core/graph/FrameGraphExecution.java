package dev.luxloader.core.graph;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.debug.RenderObserver;
import dev.luxloader.api.debug.RenderObservers;
import dev.luxloader.api.frame.FrameContext;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.RenderPass;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Executes a compiled graph. Pipelines first declare passes without recording; only after ordering is
 * known does execution invoke each encode method, enabling correct barriers/submission order. Collect
 * isolated pass failures as NodeFailure so one failure does not escape into the game loop. Invalid or
 * empty graphs execute nothing and return ran=false.
 */
public final class FrameGraphExecution {

    private FrameGraphExecution() {
    }

    /**
     * Execution result.
     * @param ran whether execution occurred
     * @param compiled read-only compilation result
     * @param failures failing passes
     * @param executed successful pass count
     * @param nanos execution time including command recording
     */
    public record Outcome(boolean ran, FrameGraph.Compiled compiled, List<NodeFailure> failures,
                          int executed, long nanos) {

        public Outcome {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        /** Whether any pass failed. */
        public boolean hasFailures() {
            return !failures.isEmpty();
        }

        /** One-line log/diagnostic description. */
        public String describe() {
            if (!ran) {
                return tr("Not executed (") + (compiled == null || compiled.error().isEmpty()
                        ? tr("no passes") : compiled.error()) + "）";
            }
            String base = tr("Executed ") + executed + tr(" passes in ")
                    + String.format(java.util.Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
            return hasFailures() ? base + "，" + failures.size() + tr(" failed passes") : base;
        }
    }

    /**
     * Failed pass including its instance so the loader can disable it and avoid repeated per-frame errors.
     * @param nodeId node ID
     * @param passName display name
     * @param pass failed instance
     * @param cause exception
     */
    public record NodeFailure(GpuId nodeId, String passName,
                              dev.luxloader.api.pipeline.RenderPass pass, Throwable cause) {

        public NodeFailure {
            Objects.requireNonNull(nodeId, "nodeId");
            passName = passName == null ? "" : passName;
            Objects.requireNonNull(cause, "cause");
        }

        /** One-line description. */
        public String describe() {
            return nodeId + "（" + passName + tr(") failed: ") + cause;
        }
    }

    /**
     * Executes in compiled order.
     * @param graph graph supplying pass resources
     * @param compiled compilation result
     * @param frame frame context
     * @param log optional failure callback
     */
    public static Outcome run(FrameGraph graph, FrameGraph.Compiled compiled, FrameContext frame,
                              Consumer<String> log) {
        return run(graph, compiled, frame, log, null);
    }

    /**
     * Execution with an optional queue-batch callback. Submitting at family transitions allows completed
     * recordings to run while later batches are recorded.
     * @param onQueueBatch receives the finished family and whether it is the last batch. The final frame
     * wait covers the last batch, so it needs no extra semaphore. Null preserves the four-argument
     * overload behavior
     */
    public static Outcome run(FrameGraph graph, FrameGraph.Compiled compiled, FrameContext frame,
                              Consumer<String> log,
                              java.util.function.BiConsumer<Integer, Boolean> onQueueBatch) {
        Objects.requireNonNull(graph, "graph");
        if (compiled == null || !compiled.valid() || compiled.ordered().isEmpty()) {
            return new Outcome(false, compiled, List.of(), 0, 0L);
        }

        long started = System.nanoTime();
        List<NodeFailure> failures = new ArrayList<>();
        int executed = 0;
        int pendingFamily = -1;
        List<RenderObserver> observers = RenderObservers.active() ? RenderObservers.current() : List.of();

        for (FrameGraph.PassNode node : compiled.ordered()) {
            if (!node.isEnabled()) {
                continue;
            }
            if (onQueueBatch != null) {
                int family = queueFamilyOf(node.pass());
                if (pendingFamily >= 0 && family != pendingFamily) {
                    // Submit the previous family before recording the next so separate queues can overlap. This is not the final batch.
                    onQueueBatch.accept(pendingFamily, false);
                }
                pendingFamily = family;
            }
            String timingLabel = node.nodeId().toString();
            if (frame.commands() != null) frame.commands().beginTimestamp(timingLabel);
            if (!observers.isEmpty() && graph instanceof FrameGraphImpl implementation) {
                implementation.useResourceProvider(node.pass().resources());
            }
            observe(observers, graph, node, frame, RenderObserver.Phase.BEFORE_ENCODE, null, log);
            try {
                node.pass().encode(graph, frame);
                executed++;
                observe(observers, graph, node, frame, RenderObserver.Phase.AFTER_ENCODE, null, log);
            } catch (RuntimeException | Error e) {
                // Isolate pass exceptions and Errors rather than propagating them into the host render loop.
                NodeFailure failure = new NodeFailure(node.nodeId(), node.pass().name(),
                        node.pass(), e);
                failures.add(failure);
                observe(observers, graph, node, frame, RenderObserver.Phase.ENCODE_FAILED, e, log);
                if (log != null) {
                    log.accept(failure.describe());
                }
            } finally {
                if (frame.commands() != null) frame.commands().endTimestamp(timingLabel);
            }
        }
        if (onQueueBatch != null && pendingFamily >= 0) {
            // The final loop batch is the last submission.
            onQueueBatch.accept(pendingFamily, true);
        }

        return new Outcome(true, compiled, failures, executed, System.nanoTime() - started);
    }

    private static void observe(List<RenderObserver> observers, FrameGraph graph,
                                FrameGraph.PassNode node, FrameContext frame,
                                RenderObserver.Phase phase, Throwable failure, Consumer<String> log) {
        if (observers.isEmpty() || !RenderObservers.active()) {
            return;
        }
        java.util.LinkedHashMap<String, RenderObserver.ResourceView> resources = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(node.inputNames());
        names.addAll(node.outputNames());
        int queueFamily = graph.compiled().queueGroups().stream()
                .filter(group -> group.nodes().stream().anyMatch(candidate -> candidate.nodeId().equals(node.nodeId())))
                .map(FrameGraph.QueueGroup::queueFamily).findFirst()
                .orElse(frame.queue() == null ? 0 : frame.queue().familyIndex());
        for (String name : names) {
            boolean reads = node.inputNames().contains(name);
            boolean writes = node.outputNames().contains(name);
            var access = reads && writes ? RenderObserver.AccessKind.READ_WRITE
                    : reads ? RenderObserver.AccessKind.READ : RenderObserver.AccessKind.WRITE;
            String layout = graph.texture(name).map(frame.commands()::imageLayout).orElse("UNKNOWN");
            resources.put(name, new RenderObserver.ResourceView(name, access,
                    graph.resourceInfo(name), layout, queueFamily));
        }
        var event = new RenderObserver.PassEvent(frame.timing().frameIndex(), node.nodeId().toString(),
                node.pass().name(), phase, resources, node, graph, frame, failure);
        for (RenderObserver observer : observers) {
            try {
                observer.onPassBoundary(event);
            } catch (RuntimeException | LinkageError observerFailure) {
                if (log != null) {
                    log.accept("Render observer failed for " + node.nodeId() + " at " + phase + ": " + observerFailure);
                }
            }
        }
    }

    /** Declared family, or graphics family zero by default; keep consistent with compilation. */
    private static int queueFamilyOf(RenderPass pass) {
        if (pass == null) {
            return 0;
        }
        var queue = pass.queue();
        return queue == null ? 0 : queue.familyIndex();
    }
}
