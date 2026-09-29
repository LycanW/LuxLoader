package dev.luxloader.api.pipeline;

import java.util.List;
import java.util.Objects;

/**
 * Immutable pipeline description used for registration, capability checks, selection and UI. {@link
 * RenderPipeline#hostSceneMode()} determines whether the host draws its world.
 * @param id unique ID
 * @param name display name
 * @param version version
 * @param kind pipeline kind
 * @param priority higher values win when pipelines compete for a role/frame ownership
 * @param exclusive legacy compatibility flag; hostSceneMode selects the actual scene path
 * @param experimental requires explicit opt-in
 * @param description description
 * @param requirements runtime prerequisites
 */
public record PipelineDescriptor(
        dev.luxloader.api.GpuId id,
        String name,
        String version,
        PipelineKind kind,
        int priority,
        boolean exclusive,
        boolean experimental,
        String description,
        Requirements requirements,
        FrameOwnership frameOwnership) {

    /**
     * Legacy frame ownership retained for registration compatibility and presentation metadata. {@link
     * RenderPipeline#hostSceneMode()} selects host participation. SHARED augments normal game rendering;
     * FRAME is the legacy exclusive declaration, and plugins drawing their scene must also return
     * PLUGIN_SCENE. SCENE kind promotes ownership to FRAME and enables exclusive; explicit FRAME also
     * enables exclusive. Plugins using PLUGIN_SCENE must validate scene input capabilities.
     */
    public enum FrameOwnership {
        /** Shared frame: the game renders and the pipeline adds passes ordered by resource dependencies. */
        SHARED,
        /** Exclusive frame: the game stops scene drawing and the plugin owns acquire, rendering and presentation. */
        FRAME
    }

    /** Pipeline category defining its relationship to vanilla rendering. */
    public enum PipelineKind {
        /** Augment host rendering with extra passes, such as postprocessing after the game draws. */
        ENHANCEMENT,
        /** Own scene drawing with custom shaders and geometry. Scene pipelines implicitly request exclusive rendering. */
        SCENE,
        /** Own the postprocessing chain, such as tone mapping, bloom or grading. */
        POST_PROCESS,
        /**
         * Presentation pipeline for frame generation, async reprojection or image warping. Such pipelines
         * often submit at display refresh cadence; see {@link dev.luxloader.api.plugin.FrameControl}.
         */
        PRESENT,
        /** Diagnostic visualization of depth, normals, acceleration structures or other intermediate data. */
        DEBUG
    }

    public PipelineDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        kind = kind == null ? PipelineKind.ENHANCEMENT : kind;
        description = description == null ? "" : description;
        requirements = requirements == null ? Requirements.none() : requirements;
        frameOwnership = frameOwnership == null ? FrameOwnership.SHARED : frameOwnership;
        if (kind == PipelineKind.SCENE) {
            // Scene pipelines must replace host scene drawing; infer exclusivity to prevent duplicate geometry when a flag is omitted.
            frameOwnership = FrameOwnership.FRAME;
        }
        if (kind == PipelineKind.SCENE && !exclusive) {
            // Scene pipelines disable host drawing automatically to prevent duplicate geometry from an omitted flag.
            exclusive = true;
        }
    }

    /** Whether to own final presentation timing. */
    public boolean ownsPresent() {
        return kind == PipelineKind.PRESENT || ownsWholeFrame();
    }

    /** Legacy exclusivity flag; runtime decisions use hostSceneMode. */
    public boolean suppressesGameRendering() {
        return exclusive || kind == PipelineKind.SCENE || frameOwnership == FrameOwnership.FRAME;
    }

    /** Whether the plugin owns the entire acquire-render-present frame. */
    public boolean ownsWholeFrame() {
        return frameOwnership == FrameOwnership.FRAME;
    }

    public Builder toBuilder() {
        Builder b = new Builder(id, name, version)
                .kind(kind)
                .priority(priority)
                .exclusive(exclusive)
                .experimental(experimental)
                .description(description)
                .requirements(requirements)
                .frameOwnership(frameOwnership);
        return b;
    }

    public static Builder builder(dev.luxloader.api.GpuId id, String name, String version) {
        return new Builder(id, name, version);
    }

    /** Order by descending priority, then lexical ID for deterministic selection across runs and machines. */
    public static java.util.Comparator<PipelineDescriptor> byPriorityThenId() {
        return java.util.Comparator.comparingInt(PipelineDescriptor::priority).reversed()
                .thenComparing(PipelineDescriptor::id);
    }

    /** Diagnostic summary. */
    public String describe() {
        return name + " " + version + " [" + id + "] kind=" + kind
                + " priority=" + priority + " frame=" + frameOwnership
                + (experimental ? " experimental" : "");
    }

    /** Descriptor builder. */
    public static final class Builder {
        private final dev.luxloader.api.GpuId id;
        private final String name;
        private final String version;
        private PipelineKind kind = PipelineKind.ENHANCEMENT;
        private int priority;
        private boolean exclusive;
        private boolean experimental;
        private String description = "";
        private Requirements requirements = Requirements.none();
        private FrameOwnership frameOwnership = FrameOwnership.SHARED;

        private Builder(dev.luxloader.api.GpuId id, String name, String version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }

        public Builder kind(PipelineKind kind) {
            this.kind = kind;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }


        public Builder exclusive(boolean exclusive) {
            this.exclusive = exclusive;
            return this;
        }

        public Builder experimental(boolean experimental) {
            this.experimental = experimental;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder requirements(Requirements requirements) {
            this.requirements = requirements;
            return this;
        }

        /**
         * Declares frame ownership. FRAME pipelines drawing the whole scene consume SceneSnapshot geometry and
         * require host.scene_extraction. HostSceneMode controls the actual host scene path.
         */
        public Builder frameOwnership(FrameOwnership ownership) {
            this.frameOwnership = ownership;
            if (ownership == FrameOwnership.FRAME) {
                // Owning the frame necessarily replaces host scene rendering.
                this.exclusive = true;
            }
            return this;
        }

        /** Declare exclusive frame ownership. */
        public Builder ownsWholeFrame() {
            return frameOwnership(FrameOwnership.FRAME);
        }

        public PipelineDescriptor build() {
            return new PipelineDescriptor(id, name, version, kind, priority, exclusive,
                    experimental, description, requirements, frameOwnership);
        }
    }


}
