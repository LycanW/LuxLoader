package dev.luxloader.api.pipeline;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.gpu.ComputePipelineDesc;

/**
 * Pass base class managing scoped resources, enabled state and required-pass flags. Implement
 * doInitialize and doEncode.
 * <pre>{@code
 * final class UpscalePass extends PassHost {
 *     private ComputePipelineDesc pipeline;
 *     UpscalePass(GpuId id, RenderPipeline owner) {
 *         super(id, "Upscale", StageKind.SUPER_RESOLUTION, owner);
 *         // Declare resource access for execution ordering and barriers.
 *         reads(FrameGraph.COLOR);
 *         writes("output");
 *     }
 *     @Override protected ResourceRequest doInitialize(RenderContext ctx) {
 *         pipeline = ComputePipelineDesc.compute("upscale", "/shaders/upscale.comp.spv", 32,
 *                 List.of(Binding.sampler(0, FrameGraph.COLOR), Binding.storage(1, "output")),
 *                 16, 16);
 *         return ResourceRequest.builder()
 *                 .image(ImageDesc.storage("upscale-out", ctx.renderWidth(), ctx.renderHeight(),
 *                         GpuFormat.R16G16B16A16_SFLOAT))
 *                 .build();
 *     }
 *     @Override protected void doEncode(FrameGraph graph, FrameContext frame) {
 *         // Record commands with graph inputs and pass-owned outputs.
 *         var color = graph.texture(FrameGraph.COLOR).orElseThrow();
 *         var output = resources.image(myOutputDesc);
 *         var commands = frame.commands().begin("upscale");
 *         commands.bindComputePipeline(pipeline);
 *         commands.writeImage(FrameGraph.COLOR, color,
 * ImageViewDesc.sampled(GpuFormat.R16G16B16A16_SFLOAT));
 *         commands.writeImage("output", output, ImageViewDesc.storage(GpuFormat.R16G16B16A16_SFLOAT));
 *         commands.dispatch(groupsX, groupsY, 1);
 *         commands.end();
 *     }
 * }
 * }</pre>
 * Use reads/writes for ordering and barriers; dependsOn adds constraints that resource access cannot
 * express. Every declared descriptor must be written before dispatch. The current compatibility hook
 * provides composed color but cannot expose the game frame graph's depth/motion images. Use separate
 * pipelines or placeholder textures for optional inputs; unwritten descriptors are undefined behavior
 * and rejected before dispatch.
 */
public abstract class PassHost implements RenderPass {

    private final GpuId id;
    private final String name;
    private final StageKind kind;
    private final RenderPipeline owner;
    private final java.util.List<RenderPass> dependencies = new java.util.ArrayList<>();
    private final java.util.Set<String> inputs = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> outputs = new java.util.LinkedHashSet<>();

    private GpuResourceProvider resources;
    private boolean enabled = true;
    private boolean required = true;
    private RenderContext context;

    protected PassHost(GpuId id, String name, StageKind kind, RenderPipeline owner) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.name = java.util.Objects.requireNonNull(name, "name");
        this.kind = kind == null ? StageKind.OTHER : kind;
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
    }

    @Override
    public final GpuId id() {
        return id;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final StageKind kind() {
        return kind;
    }

    /** Owning pipeline for access to shared state and sibling passes. */
    public final RenderPipeline owner() {
        return owner;
    }

    @Override
    public final java.util.Set<String> inputs() {
        return java.util.Set.copyOf(inputs);
    }

    @Override
    public final java.util.Set<String> outputs() {
        return java.util.Set.copyOf(outputs);
    }

    @Override
    public final GpuResourceProvider resources() {
        return resources;
    }

    /** Initialization context, valid throughout the pass lifetime. */
    protected final RenderContext context() {
        return context;
    }

    /**
     * Creates the declared compute pipeline. Call once in {@link #doInitialize}; the pass owns the actual
     * compiled/cached SPIR-V and the loader cannot infer it from the shader path. Later
     * bindComputePipeline calls look up this registration by name.
     */
    protected final void createComputePipeline(ComputePipelineDesc desc, byte[] spirv) {
        java.util.Objects.requireNonNull(desc, "desc");
        context.device().createComputePipeline(desc, spirv);
    }

    @Override
    public final ResourceRequest initialize(RenderContext ctx) {
        this.context = java.util.Objects.requireNonNull(ctx, "ctx");
        this.resources = ctx.resources();
        ResourceRequest request = doInitialize(ctx);
        return request == null ? ResourceRequest.EMPTY : request;
    }

    @Override
    public final void encode(FrameGraph graph, dev.luxloader.api.frame.FrameContext frame) {
        if (!enabled) {
            return;
        }
        doEncode(graph, frame);
    }

    @Override
    public final boolean required() {
        return required;
    }

    @Override
    public final boolean enabled() {
        return enabled;
    }

    @Override
    public final void dependsOn(RenderPass previous) {
        if (previous != null && previous != this) {
            dependencies.add(previous);
        }
    }

    @Override
    public void close() {
        doClose();
    }

    // Subclass implementation hooks.

    /** Create pipelines and resources, returning resource requirements. */
    protected abstract ResourceRequest doInitialize(RenderContext ctx);

    /** Record this frame's work. */
    protected abstract void doEncode(FrameGraph graph, dev.luxloader.api.frame.FrameContext frame);

    /** Optional resource cleanup. */
    protected void doClose() {
    }

    // Subclass utilities.

    /** Declare a resource read. */
    protected final PassHost reads(String... names) {
        for (String n : names) {
            inputs.add(PassNodeGuard.require(n));
        }
        return this;
    }

    /** Declare a resource write. */
    protected final PassHost writes(String... names) {
        for (String n : names) {
            outputs.add(PassNodeGuard.require(n));
        }
        return this;
    }

    /** Runtime enable/disable control suitable for UI binding. */
    public final void setEnabled(boolean value) {
        this.enabled = value;
    }

    /** Mark optional: failures produce warnings instead of aborting required work. */
    protected final void optional() {
        this.required = false;
    }

    /** Pass dependencies for frame graph ordering. */
    public final java.util.List<RenderPass> dependencies() {
        return java.util.List.copyOf(dependencies);
    }

    /** Validate a resource name. */
    private static final class PassNodeGuard {
        static String require(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(tr("Resource name must not be empty"));
            }
            return name.trim();
        }
    }
}
