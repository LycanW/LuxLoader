package dev.luxloader.core.graph;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.RenderPass;
import dev.luxloader.api.pipeline.StageKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles enabled passes into a deterministic, acyclic queue-grouped sequence. Execution follows
 * resource versions, explicit dependencies and declaration order, without insertion-point enums.
 * Collect all writers first, chain same-resource writers in declaration order, anchor readers to the
 * latest preceding writer (or the first writer), and make the next writer wait for those readers. This
 * supports ping-pong updates without artificial cycles from depending on every writer. Add explicit
 * after/before/dependsOn and frame-boundary edges, then use Kahn sorting by descending fan-out,
 * descending priority and ascending declaration order. Reject cycles with their participating nodes. A
 * sole writer precedes its readers regardless of declaration order; multiple writers intentionally
 * expose different versions to readers declared between them.
 */
public final class FrameGraphCompiler {

    /** Defensive limit on nodes per frame graph. */
    public static final int MAX_NODES = 512;

    private FrameGraphCompiler() {
    }

    /**
     * Compiles nodes in declaration order.
     * @param nodes declared nodes
     * @return result with valid=false on fatal errors, causing the loader to skip execution
     */
    public static FrameGraph.Compiled compile(List<Node> nodes) {
        return compile(nodes, Set.of());
    }

    /** Imported resources have a readable version before their first declared writer. */
    public static FrameGraph.Compiled compile(List<Node> nodes, Set<String> importedResources) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(importedResources, "importedResources");
        List<String> warnings = new ArrayList<>();

        List<Node> enabled = new ArrayList<>();
        for (Node n : nodes) {
            if (n.enabled) {
                enabled.add(n);
            }
        }

        if (enabled.isEmpty()) {
            return new FrameGraph.Compiled(List.of(), 0, List.of(), warnings, true, "");
        }
        if (enabled.size() > MAX_NODES) {
            return FrameGraph.Compiled.invalid(tr("Frame graph node count ") + enabled.size() + tr(" exceeds limit ") + MAX_NODES);
        }

        // Collect every writer before building edges. A single scan would miss dependencies when a reader is declared before its writer.
        Map<String, List<Node>> writers = new LinkedHashMap<>();
        for (Node n : enabled) {
            for (String out : n.outputs) {
                writers.computeIfAbsent(out, k -> new ArrayList<>()).add(n);
            }
        }

        // Build resource version chains: serialize writers in declaration order, anchor each reader to its latest preceding writer (or the first), and make the next writer wait for consumption. Depending on all writers would create false cycles during chained rewrites.
        for (Map.Entry<String, List<Node>> entry : writers.entrySet()) {
            List<Node> chain = entry.getValue();
            for (int i = 1; i < chain.size(); i++) {
                chain.get(i).addDependency(chain.get(i - 1),
                        tr("write ") + entry.getKey() + tr(" (previous writer)"));
            }
        }

        for (Map.Entry<String, List<Node>> entry : writers.entrySet()) {
            String resource = entry.getKey();
            List<Node> chain = entry.getValue();
            for (Node reader : enabled) {
                if (!reader.inputs.contains(resource)) {
                    continue;
                }
                if (chain.contains(reader)) {
                    // An in-place read/write is already ordered after the previous writer by the version chain. Extra edges would create a contradictory dependency.
                    continue;
                }
                if (importedResources.contains(resource) && reader.sequence < chain.get(0).sequence) {
                    // Read the imported value before the first pass overwrites it.
                    chain.get(0).addDependency(reader, tr("overwrite imported resource ") + resource);
                    continue;
                }
                Node anchor = anchorFor(chain, reader);
                reader.addDependency(anchor, tr("read ") + resource);
                int index = chain.indexOf(anchor);
                if (index + 1 < chain.size()) {
                    chain.get(index + 1).addDependency(reader,
                            tr("write ") + resource + tr(" (wait for readers of the previous version)"));
                }
            }
        }

        // Warn about inputs without writers only for plugin resources. Host frame.* inputs and boundary markers legitimately have no pass writer.
        for (Node n : enabled) {
            for (String in : n.inputs) {
                List<Node> producers = writers.get(in);
                if (producers != null && !producers.isEmpty()) {
                    continue;
                }
                if (!isHostResource(in)) {
                    warnings.add(n.nodeId() + tr("FrameGraphCompiler.ed974d116c", " reads ") + in
                            + tr(", but no pass writes it this frame (valid for host-provided resources)"));
                }
            }
        }

        // Explicit dependencies.
        for (Node n : enabled) {
            for (Node dep : n.explicitDependencies) {
                if (dep.enabled && dep != n) {
                    n.addDependency(dep, tr("Explicit declaration"));
                }
            }
        }

        // Frame boundaries.
        String boundaryError = applyBoundaries(enabled, warnings);
        if (boundaryError != null) {
            return new FrameGraph.Compiled(List.of(), 0, List.of(), warnings, false, boundaryError);
        }

        // Kahn sort: descending fan-out, descending priority, then declaration order for deterministic results.
        Map<Node, Integer> fanOut = new LinkedHashMap<>();
        for (Node n : enabled) {
            fanOut.put(n, n.successors.size());
        }

        List<Node> ordered = new ArrayList<>(enabled.size());
        Map<Node, Integer> inDegree = new LinkedHashMap<>();
        for (Node n : enabled) {
            inDegree.put(n, n.dependencies.size());
        }

        java.util.PriorityQueue<Node> ready = new java.util.PriorityQueue<>(
                java.util.Comparator
                        .comparingInt((Node n) -> -fanOut.getOrDefault(n, 0))
                        .thenComparingInt(n -> -n.priority)
                        .thenComparingInt(n -> n.sequence));

        for (Map.Entry<Node, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }

        while (!ready.isEmpty()) {
            Node n = ready.poll();
            ordered.add(n);
            for (Node succ : n.successors) {
                Integer deg = inDegree.get(succ);
                if (deg == null) {
                    continue;
                }
                int next = deg - 1;
                inDegree.put(succ, next);
                if (next == 0) {
                    ready.add(succ);
                }
            }
        }

        if (ordered.size() != enabled.size()) {
            List<Node> cycle = findCycle(enabled);
            String detail = cycle.isEmpty()
                    ? tr("Cyclic dependency")
                    : tr("Cyclic dependency: ") + String.join(" -> ", cycle.stream().map(n -> n.nodeId().toString()).toList());
            return new FrameGraph.Compiled(List.of(), 0, List.of(), warnings, false, detail);
        }

        List<FrameGraph.QueueGroup> groups = groupByQueue(ordered);
        int barriers = estimateBarriers(ordered);

        return new FrameGraph.Compiled(
                ordered.stream().map(n -> (FrameGraph.PassNode) n).toList(),
                barriers, groups, warnings, true, "");
    }

    /**
     * Selects the latest writer declared before the reader, or the first writer if the reader precedes
     * them all. Later writers must wait until that version is consumed.
     */
    static Node anchorFor(List<Node> chain, Node reader) {
        Node anchor = chain.get(0);
        for (Node writer : chain) {
            if (writer.sequence < reader.sequence) {
                anchor = writer;
            }
        }
        return anchor;
    }

    /**
     * Adds minimal frame-boundary edges. FRAME_BEGIN readers precede ordinary passes; FRAME_END writers
     * follow them. Boundary peers are not ordered against each other beyond existing resource/declaration
     * constraints. A node claiming both boundaries is valid only in a single-node graph.
     * @return readable error, or null on success
     */
    static String applyBoundaries(List<Node> enabled, List<String> warnings) {
        List<Node> firsts = new ArrayList<>();
        List<Node> lasts = new ArrayList<>();
        for (Node n : enabled) {
            if (n.inputs.contains(FrameGraph.FRAME_BEGIN)) {
                firsts.add(n);
            }
            if (n.outputs.contains(FrameGraph.FRAME_END)) {
                lasts.add(n);
            }
        }
        if (firsts.isEmpty() && lasts.isEmpty()) {
            return null;
        }

        if (enabled.size() == 1) {
            // With one node, first and last do not conflict.
            return null;
        }

        for (Node n : firsts) {
            if (lasts.contains(n)) {
                return n.nodeId() + tr("FrameGraphCompiler.ed974d116c", " reads ") + FrameGraph.FRAME_BEGIN
                        + tr(" and writes ") + FrameGraph.FRAME_END
                        + tr(": this requires both first and last position, which is possible only for a single-pass graph. ")
                        + tr("Split these responsibilities into separate passes.");
            }
        }

        if (firsts.size() > 1) {
            warnings.add(tr("There are ") + firsts.size() + tr(" passes reading ") + FrameGraph.FRAME_BEGIN
                    + tr("; all run first, ordered by resource dependencies and declaration order"));
        }
        if (lasts.size() > 1) {
            warnings.add(tr("There are ") + lasts.size() + tr(" passes writing ") + FrameGraph.FRAME_END
                    + tr("; all run last, ordered by resource dependencies and declaration order"));
        }

        for (Node first : firsts) {
            for (Node other : enabled) {
                if (other == first || firsts.contains(other)) {
                    continue;
                }
                other.addDependency(first, tr("reads ") + FrameGraph.FRAME_BEGIN);
            }
        }
        for (Node last : lasts) {
            for (Node other : enabled) {
                if (other == last || lasts.contains(other)) {
                    continue;
                }
                last.addDependency(other, tr("writes ") + FrameGraph.FRAME_END);
            }
        }
        return null;
    }

    /**
     * Host resource names use frame.* and need no pass writer. Suppress missing-writer warnings for them
     * so actual plugin naming errors remain visible.
     */
    static boolean isHostResource(String name) {
        return name != null && name.startsWith("frame.");
    }

    /** Finds a cycle with iterative DFS, returning a closed node path. Avoids recursion overflow on deep graphs. */
    static List<Node> findCycle(List<Node> nodes) {
        Map<Node, Integer> state = new LinkedHashMap<>(); // 0=unvisited, 1=on stack, 2=completed.
        Deque<Node> stack = new ArrayDeque<>();

        for (Node start : nodes) {
            if (state.getOrDefault(start, 0) != 0) {
                continue;
            }
            Deque<Node> work = new ArrayDeque<>();
            work.push(start);
            while (!work.isEmpty()) {
                Node n = work.peek();
                int st = state.getOrDefault(n, 0);
                if (st == 0) {
                    state.put(n, 1);
                    stack.push(n);
                }
                boolean advanced = false;
                for (Node succ : n.successors) {
                    int ss = state.getOrDefault(succ, 0);
                    if (ss == 1) {
                        List<Node> cycle = new ArrayList<>();
                        for (Node x : stack) {
                            cycle.add(x);
                            if (x == succ) {
                                break;
                            }
                        }
                        java.util.Collections.reverse(cycle);
                        cycle.add(succ);
                        return cycle;
                    }
                    if (ss == 0) {
                        work.push(succ);
                        advanced = true;
                        break;
                    }
                }
                if (!advanced) {
                    work.pop();
                    stack.remove(n);
                    state.put(n, 2);
                }
            }
        }
        return List.of();
    }

    /** Groups consecutive nodes by queue family while preserving topological order. */
    static List<FrameGraph.QueueGroup> groupByQueue(List<Node> ordered) {
        List<FrameGraph.QueueGroup> groups = new ArrayList<>();
        List<Node> current = new ArrayList<>();
        int currentFamily = Integer.MIN_VALUE;
        boolean sawGraphicsBefore = false;

        for (Node n : ordered) {
            int family = n.queueFamily();
            if (family != currentFamily && !current.isEmpty()) {
                groups.add(new FrameGraph.QueueGroup(currentFamily,
                        current.stream().map(x -> (FrameGraph.PassNode) x).toList(),
                        sawGraphicsBefore && currentFamily != 0));
                if (currentFamily == 0) {
                    sawGraphicsBefore = true;
                }
                current = new ArrayList<>();
            }
            currentFamily = family;
            current.add(n);
        }
        if (!current.isEmpty()) {
            groups.add(new FrameGraph.QueueGroup(currentFamily,
                    current.stream().map(x -> (FrameGraph.PassNode) x).toList(),
                    sawGraphicsBefore && currentFamily != 0));
        }
        return groups;
    }

    /** Estimates barriers from resource access between neighboring nodes. */
    static int estimateBarriers(List<Node> ordered) {
        int count = 0;
        Set<String> written = new HashSet<>();
        for (Node n : ordered) {
            boolean needs = false;
            for (String in : n.inputs) {
                if (written.contains(in)) {
                    needs = true;
                    break;
                }
            }
            if (!needs && !n.outputs.isEmpty() && !written.isEmpty()) {
                needs = true;
            }
            if (needs) {
                count++;
            }
            written.addAll(n.outputs);
        }
        return count;
    }

    /** Mutable graph node used while constructing edges; treated as read-only after compilation. */
    public static final class Node implements FrameGraph.PassNode {

        private final GpuId nodeId;
        private final RenderPass pass;
        private final StageKind kind;
        private final int priority;
        private final int sequence;
        private final int queueFamily;

        private final Set<String> inputs = new LinkedHashSet<>();
        private final Set<String> outputs = new LinkedHashSet<>();
        private final List<Node> successors = new ArrayList<>();
        private final List<Node> predecessors = new ArrayList<>();
        private final Set<Node> dependencies = new LinkedHashSet<>();
        private final List<Node> explicitDependencies = new ArrayList<>();
        /** Deferred before-X constraints, resolved after all nodes exist. */
        private final List<RenderPass> successorHints = new ArrayList<>();

        private boolean enabled = true;
        private boolean allowParallel;

        public Node(GpuId nodeId, RenderPass pass, StageKind kind,
                    int priority, int sequence, int queueFamily) {
            this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
            this.pass = Objects.requireNonNull(pass, "pass");
            this.kind = kind == null ? StageKind.OTHER : kind;
            this.priority = priority;
            this.sequence = sequence;
            this.queueFamily = queueFamily;
            this.inputs.addAll(pass.inputs());
            this.outputs.addAll(pass.outputs());
            // Inherit pass enablement so temporarily disabled passes stay disabled.
            this.enabled = pass.enabled();
        }

        /** Pass dependencies declared through PassHost.dependsOn. */
        private static List<RenderPass> dependencyHint(RenderPass pass) {
            if (pass instanceof dev.luxloader.api.pipeline.PassHost host) {
                return host.dependencies();
            }
            return List.of();
        }

        /** Resolves pass dependency objects into nodes before compilation. */
        public void resolveDependencies(Map<RenderPass, Node> byPass) {
            for (RenderPass dep : dependencyHint(pass)) {
                Node n = byPass.get(dep);
                if (n != null) {
                    explicitDependencies.add(n);
                }
            }
        }

        /**
         * Resolves before-X constraints as X.addDependency(this). Wait until all nodes exist, like
         * resolveDependencies; earlier resolution would silently lose constraints.
         */
        public void resolveSuccessors(Map<RenderPass, Node> byPass) {
            for (RenderPass later : successorHints) {
                Node n = byPass.get(later);
                if (n != null && n != this) {
                    n.addDependency(this, tr("Runtime declaration: must precede ") + n.nodeId());
                }
            }
        }

        void addDependency(Node from, String reason) {
            if (from == null || from == this || dependencies.contains(from)) {
                return;
            }
            dependencies.add(from);
            predecessors.add(from);
            from.successors.add(this);
        }

        boolean hasDependency(Node other) {
            return dependencies.contains(other);
        }

        int queueFamily() {
            return queueFamily;
        }

        @Override
        public GpuId nodeId() {
            return nodeId;
        }

        @Override
        public RenderPass pass() {
            return pass;
        }

        @Override
        public StageKind kind() {
            return kind;
        }

        @Override
        public FrameGraph.PassNode after(RenderPass other) {
            for (Node n : predecessors) {
                if (n.pass() == other) {
                    return this;
                }
            }
            for (Node candidate : explicitDependencies) {
                if (candidate.pass() == other) {
                    addDependency(candidate, tr("Runtime declaration"));
                    return this;
                }
            }
            return this;
        }

        @Override
        public FrameGraph.PassNode before(RenderPass other) {
            // Record the before-X intent until X exists and resolveSuccessors can add the inverse dependency.
            if (other != null && !successorHints.contains(other)) {
                successorHints.add(other);
            }
            return this;
        }

        @Override
        public FrameGraph.PassNode reads(String... resourceNames) {
            for (String r : resourceNames) {
                inputs.add(r);
            }
            return this;
        }

        @Override
        public FrameGraph.PassNode writes(String... resourceNames) {
            for (String r : resourceNames) {
                outputs.add(r);
            }
            return this;
        }

        @Override
        public FrameGraph.PassNode allowParallel(boolean allow) {
            this.allowParallel = allow;
            return this;
        }

        @Override
        public FrameGraph.PassNode enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public Set<String> inputNames() {
            return Set.copyOf(inputs);
        }

        @Override
        public Set<String> outputNames() {
            return Set.copyOf(outputs);
        }

        @Override
        public List<FrameGraph.PassNode> successors() {
            return List.copyOf(successors);
        }

        @Override
        public List<FrameGraph.PassNode> predecessors() {
            return List.copyOf(predecessors);
        }

        /** Whether the author marked the node as parallelizable. */
        public boolean parallelAllowed() {
            return allowParallel;
        }

        /** Direct dependency count for compile diagnostics. */
        public int dependencyCount() {
            return dependencies.size();
        }
    }
}
