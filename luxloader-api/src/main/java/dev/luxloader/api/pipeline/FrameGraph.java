package dev.luxloader.api.pipeline;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.gpu.ImageHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import dev.luxloader.api.gpu.ImageDesc;

/**
 * Frame graph declaration of passes and their resource access. Pipelines add passes in {@code
 * RenderPipeline#encodeFrame}; the loader derives a topological order from writers/readers, explicit
 * dependencies, write-after-write serialization and declaration order, inserts resource barriers, and
 * groups queue submissions with cross-queue semaphore waits. <p>Host resources use the {@code frame.}
 * namespace; plugin resources use their own prefix. Optional host inputs are: {@code frame.color},
 * {@code frame.depth} and {@code frame.motion} at render resolution; {@code frame.exposure} at 1x1;
 * and {@code frame.ui}, {@code frame.history} and {@code frame.swapchain} at display resolution.
 * History is the previous pipeline output. Frame boundaries are also resources: {@code frame.begin},
 * {@code frame.end} and {@code frame.final}. Any resource may be absent; check its Optional instead of
 * assuming availability.
 */
public interface FrameGraph {

    /** Stable resource identity and the concrete facts needed to inspect its bytes safely. */
    record ResourceInfo(String id, String ownerId, dev.luxloader.api.pipeline.GpuResourceProvider.Ownership ownership,
                        long contentVersion, ImageDesc image, String currentLayout, int queueFamily,
                        String payloadLayout, Map<String, String> metadata, List<String> missingReasons) {
        public ResourceInfo {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Resource id must not be blank");
            }
            ownerId = ownerId == null ? "" : ownerId;
            ownership = ownership == null ? GpuResourceProvider.Ownership.UNKNOWN : ownership;
            if (contentVersion < 0) {
                throw new IllegalArgumentException("contentVersion must not be negative");
            }
            currentLayout = currentLayout == null ? "" : currentLayout;
            payloadLayout = payloadLayout == null ? "" : payloadLayout;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            missingReasons = missingReasons == null ? List.of() : List.copyOf(missingReasons);
        }
    }

    // Host resources (read-only for pipelines).

    /** Host scene color at render resolution. */
    String COLOR = "frame.color";
    /** Host depth at render resolution. */
    String DEPTH = "frame.depth";
    /** Host motion vectors at render resolution; many rendering paths do not supply them. */
    String MOTION = "frame.motion";
    /** Automatic exposure, usually 1x1. */
    String EXPOSURE = "frame.exposure";
    /** Host-composited UI at display resolution. */
    String UI = "frame.ui";
    /** Current swapchain image at display resolution. */
    String SWAPCHAIN = "frame.swapchain";
    /** Previous final output for temporal effects. */
    String HISTORY = "frame.history";

    // Frame boundaries express ordering through resources. Reading BEGIN precedes all passes; writing END follows all passes. The compiler's topological sort enforces these constraints without restricting passes to fixed insertion-point enums.

    /** Frame-begin marker: readers run first. */
    String FRAME_BEGIN = "frame.begin";
    /** Frame-end marker: writers run last. */
    String FRAME_END = "frame.end";
    /** Final output: the writing pass determines presentation content. */
    String FINAL = "frame.final";

    // Example names for plugin-owned resources; plugins may choose their own namespace.

    /** Pipeline-owned scene color for scene rendering. */
    String MY_COLOR = "my.color";
    /** Pipeline-owned depth. */
    String MY_DEPTH = "my.depth";
    /** Pipeline-owned normals. */
    String MY_NORMAL = "my.normal";
    /** Pipeline-owned material/AO data. */
    String MY_MATERIAL = "my.material";
    /** Pipeline-owned intermediate output. */
    String MY_TEMP = "my.temp";

    /**
     * Adds a pass. Ordering follows declared inputs/outputs (PassHost reads/writes): writers precede
     * readers, with declaration order resolving otherwise unconstrained passes.
     * @param pass pass instance
     * @return node supporting explicit after/reads constraints
     */
    PassNode addPass(RenderPass pass);

    /** Add a pass and immediately declare a dependency on another pass. */
    default PassNode addPass(RenderPass pass, RenderPass dependsOn) {
        PassNode node = addPass(pass);
        if (dependsOn != null) {
            node.after(dependsOn);
        }
        return node;
    }

    /**
     * Registers a resource name and description for the per-frame directory and graph diagnostics. This
     * does not allocate GPU memory; passes create actual images/buffers through {@code ctx.resources()}.
     * Register plugin-owned names to make the data flow inspectable.
     */
    void define(String resourceName, String description);

    /**
     * Publishes an output texture for later passes to retrieve by name. Resource read/write declarations
     * establish ordering but do not transfer handles. A producer calls {@code graph.publish("output",
     * image)} during encode; a consumer retrieves {@code graph.texture("output")}. Matching reads/writes
     * ensure publication precedes consumption, including across queue families.
     * @param resourceName name matching the producer's writes declaration
     * @param image output texture; null outputs are ignored
     */
    default void publish(String resourceName, ImageHandle image) {
        // Default no-op preserves compatibility. FrameGraphImpl.publish adds the resource to this frame's directory.
    }

    /** Publishes a texture together with the producer's version when it differs from provider allocation facts. */
    default void publish(String resourceName, ImageHandle image, long contentVersion) {
        publish(resourceName, image);
    }

    /** Optional metadata for a named resource; absent descriptions remain explicitly unknown to observers. */
    default Optional<ResourceInfo> resourceInfo(String resourceName) {
        return Optional.empty();
    }

    /** Look up a named texture; see predefined names in the class contract. */
    Optional<ImageHandle> texture(String resourceName);

    /** Look up a named buffer. */
    Optional<dev.luxloader.api.gpu.GpuDevice.Handle> buffer(String resourceName);

    /** Look up a named acceleration structure. */
    Optional<dev.luxloader.api.gpu.AcceleratorHandle> accel(String resourceName);

    /** All available resource names. */
    Set<String> resourceNames();

    /** Whether a pass writes this resource this frame, for example to order work before or after UI production. */
    boolean isProducedThisFrame(String resourceName);

    /** Execution result populated by the loader and readable by plugins. */
    Compiled compiled();

    /**
     * Compilation result.
     * @param ordered topologically ordered nodes
     * @param barriers automatically inserted barrier count
     * @param queueGroups queue submission batches
     * @param warnings compilation warnings, e.g. reads without a writer
     * @param valid whether execution is possible; false skips the pipeline for this frame
     * @param error reason for an invalid graph
     */
    record Compiled(
            List<PassNode> ordered,
            int barriers,
            List<QueueGroup> queueGroups,
            List<String> warnings,
            boolean valid,
            String error) {

        public static Compiled invalid(String error) {
            return new Compiled(List.of(), 0, List.of(), List.of(), false, error);
        }

        public Compiled {
            ordered = ordered == null ? List.of() : List.copyOf(ordered);
            queueGroups = queueGroups == null ? List.of() : List.copyOf(queueGroups);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            error = error == null ? "" : error;
        }

        /**
         * Last node writing {@link FrameGraph#FINAL}, which determines the presented image. Write-after-write
         * serialization keeps multiple writers in declaration order. Empty if no pass claims the final output.
         */
        public Optional<PassNode> finalProducer() {
            PassNode found = null;
            for (PassNode n : ordered) {
                if (n.outputNames().contains(FrameGraph.FINAL)) {
                    found = n;
                }
            }
            return Optional.ofNullable(found);
        }

        /** First execution node. */
        public Optional<PassNode> first() {
            return ordered.isEmpty() ? Optional.empty() : Optional.of(ordered.get(0));
        }

        /** Last execution node. */
        public Optional<PassNode> last() {
            return ordered.isEmpty() ? Optional.empty() : Optional.of(ordered.get(ordered.size() - 1));
        }

        /** Single-line execution order for diagnostics. */
        public String sequence() {
            StringBuilder sb = new StringBuilder();
            for (PassNode n : ordered) {
                if (sb.length() > 0) {
                    sb.append(" → ");
                }
                sb.append(n.nodeId().path());
            }
            return sb.toString();
        }
    }

    /**
     * Queue submission batch. The loader inserts semaphores across batches.
     * @param queueFamily queue family
     * @param nodes nodes in this batch
     * @param parallelWithPrevious whether this batch may overlap its predecessor
     */
    record QueueGroup(int queueFamily, List<PassNode> nodes, boolean parallelWithPrevious) {
        public QueueGroup {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }
    }

    /**
     * A pass invocation in a frame. A pass can be added multiple times, such as downsampling for AO and
     * bloom. Each node declares its own resource access, from which ordering is derived.
     */
    interface PassNode {

        /** Node ID, based on the pass ID with an optional suffix for repeated placement. */
        GpuId nodeId();

        /** Corresponding pass. */
        RenderPass pass();

        /** Semantic category for diagnostics only; ordering depends exclusively on resources and explicit dependencies. */
        StageKind kind();

        /** Require this node to run after another node. */
        PassNode after(RenderPass other);

        /** Require this node to run before another node; the loader resolves targets added later. */
        PassNode before(RenderPass other);

        /**
         * Appends input resource names to those inherited from {@link RenderPass#inputs()}; it does not
         * replace them. Additional reads can add ordering edges but cannot remove required dependencies.
         */
        PassNode reads(String... resourceNames);

        /** Append output resource names without replacing existing declarations. */
        PassNode writes(String... resourceNames);

        /**
         * Allow parallel scheduling. Actual overlap requires different queue families and no resource
         * dependency. Defaults to false for conservative ordering.
         */
        PassNode allowParallel(boolean allow);

        /** Whether enabled; disabled nodes are removed from execution order. */
        PassNode enabled(boolean enabled);

        boolean isEnabled();

        Set<String> inputNames();

        Set<String> outputNames();

        List<PassNode> successors();

        List<PassNode> predecessors();

        /** One-line node summary for diagnostics. */
        default String describe() {
            return nodeId() + " [" + kind() + "] in=" + inputNames() + " out=" + outputNames()
                    + (isEnabled() ? "" : " [disabled]");
        }

        /** Build a read-only snapshot for graph compilation. */
        static List<PassNode> snapshot(List<PassNode> nodes) {
            return nodes == null ? List.of() : new ArrayList<>(nodes);
        }

        /** Shared empty collection for implementations. */
        Set<String> NO_RESOURCES = Set.of();

        /** Empty predecessor/successor set. */
        List<PassNode> NO_NODES = List.of();

        /** Argument validation helper. */
        static void requireName(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException(tr("Resource name must not be empty"));
            }
        }

        /** Normalize resource names by trimming whitespace. */
        static String normalize(String name) {
            return name == null ? "" : name.trim();
        }

        /** Construct read/write sets from a map. */
        static Set<String> namesOf(Map<String, ?> map) {
            return map == null ? Set.of() : Set.copyOf(map.keySet());
        }
    }
}
