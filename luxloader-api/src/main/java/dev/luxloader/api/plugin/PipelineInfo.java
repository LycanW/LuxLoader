package dev.luxloader.api.plugin;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.pipeline.PipelineDescriptor;

/**
 * Registered pipeline snapshot for settings and diagnostics.
 * @param id pipeline ID
 * @param descriptor description
 * @param provider provider plugin ID
 * @param state current state
 * @param detail failure/fallback details
 * @param vramBytes estimated VRAM use; zero before instantiation
 */
public record PipelineInfo(
        GpuId id,
        PipelineDescriptor descriptor,
        String provider,
        State state,
        String detail,
        long vramBytes) {

    /** Pipeline lifecycle state. */
    public enum State {
        /** Registered but not instantiated; only active pipelines allocate GPU resources. */
        REGISTERED,
        /** Activation/initialization in progress. */
        ACTIVATING,
        /** Active and rendering frames. */
        ACTIVE,
        /** Rejected because device or capability requirements are unmet. */
        REJECTED,
        /** Activation or required-pass failure; users may explicitly retry. */
        FAILED,
        /** Automatically disabled after repeated runtime failures. */
        DISABLED,
        /** Closed after replacement by another active pipeline. */
        CLOSED
    }

    public PipelineInfo {
        provider = provider == null ? "" : provider;
        detail = detail == null ? "" : detail;
        state = state == null ? State.REGISTERED : state;
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    /** Whether visible to users but currently unusable. */
    public boolean isUnusable() {
        return state == State.REJECTED || state == State.FAILED || state == State.DISABLED;
    }

    /** UI name, preferring the descriptor's display name. */
    public String displayName() {
        return descriptor.name() + " " + descriptor.version();
    }

    public PipelineInfo withState(State newState, String newDetail) {
        return new PipelineInfo(id, descriptor, provider, newState, newDetail, vramBytes);
    }

    public PipelineInfo withVram(long bytes) {
        return new PipelineInfo(id, descriptor, provider, state, detail, bytes);
    }
}
