package dev.luxloader.api.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * World passes selected by an active pipeline before Minecraft executes its
 * prepared frame. The host prepares chunks and feature submissions once; the
 * pipeline decides which prepared draws enter the frame graph. PIPELINE runs
 * this pipeline's declared passes inside the host's frame command stream.
 */
public record WorldFramePlan(List<Step> steps, CelestialRotation celestialRotation) {
    public WorldFramePlan(List<Step> steps) {
        this(steps, CelestialRotation.IDENTITY);
    }

    /** PREPARED_FEATURES runs prepared feature and translucent draws without opaque chunk terrain. */
    public enum Step {
        SKY, PREPARED_SCENE, PREPARED_FEATURES,
        /** Prepared opaque/cutout terrain and opaque features, without transparency. */
        PREPARED_OPAQUE_SCENE,
        /** Only opaque features; the plugin supplies opaque/cutout terrain. */
        PREPARED_OPAQUE_FEATURES,
        /** Prepared transparency and overlays, using the current world color and depth. */
        PREPARED_TRANSPARENCY,
        /** Prepared transparent features/overlays, without translucent chunk terrain. */
        PREPARED_TRANSPARENCY_FEATURES,
        PIPELINE;

        public boolean opaqueOnly() {
            return this == PREPARED_OPAQUE_SCENE || this == PREPARED_OPAQUE_FEATURES;
        }
    }

    public WorldFramePlan {
        steps = List.copyOf(steps);
        celestialRotation = java.util.Objects.requireNonNull(celestialRotation, "celestialRotation");
        if (steps.isEmpty() || !steps.contains(Step.PIPELINE)) {
            throw new IllegalArgumentException("A world frame must execute its pipeline");
        }
        for (Step step : Step.values()) {
            if (steps.stream().filter(candidate -> candidate == step).count() > 1) {
                throw new IllegalArgumentException("Duplicate world step: " + step);
            }
        }
        if (steps.contains(Step.PREPARED_SCENE) && steps.contains(Step.PREPARED_FEATURES)) {
            throw new IllegalArgumentException("Choose one prepared world draw mode");
        }
        boolean whole = steps.contains(Step.PREPARED_SCENE) || steps.contains(Step.PREPARED_FEATURES);
        boolean split = steps.stream().anyMatch(step -> step.opaqueOnly()
                || step == Step.PREPARED_TRANSPARENCY || step == Step.PREPARED_TRANSPARENCY_FEATURES);
        if ((whole && split) || (steps.contains(Step.PREPARED_OPAQUE_SCENE)
                && steps.contains(Step.PREPARED_OPAQUE_FEATURES))
                || (steps.contains(Step.PREPARED_TRANSPARENCY) && steps.contains(Step.PREPARED_TRANSPARENCY_FEATURES))) {
            throw new IllegalArgumentException("Prepared draws must not execute twice");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Step> steps = new ArrayList<>();
        private CelestialRotation celestialRotation = CelestialRotation.IDENTITY;

        /** Rotates sun, moon and stars; the horizon, clouds and host environment stay unchanged. */
        public Builder celestialRotation(CelestialRotation rotation) {
            celestialRotation = java.util.Objects.requireNonNull(rotation, "rotation");
            return this;
        }

        public Builder sky() {
            steps.add(Step.SKY);
            return this;
        }

        public Builder preparedScene() {
            steps.add(Step.PREPARED_SCENE);
            return this;
        }

        public Builder preparedFeatures() {
            steps.add(Step.PREPARED_FEATURES);
            return this;
        }

        public Builder preparedOpaqueScene() {
            steps.add(Step.PREPARED_OPAQUE_SCENE);
            return this;
        }

        public Builder preparedOpaqueFeatures() {
            steps.add(Step.PREPARED_OPAQUE_FEATURES);
            return this;
        }

        /** The preceding stages must have written the opaque world color and depth. */
        public Builder preparedTransparency() {
            steps.add(Step.PREPARED_TRANSPARENCY);
            return this;
        }

        public Builder preparedTransparencyFeatures() {
            steps.add(Step.PREPARED_TRANSPARENCY_FEATURES);
            return this;
        }

        public Builder pipeline() {
            steps.add(Step.PIPELINE);
            return this;
        }

        public WorldFramePlan build() {
            return new WorldFramePlan(steps, celestialRotation);
        }
    }
}
