package dev.luxloader.api.capability;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.Locale;
import java.util.Objects;

/**
 * Capability support levels. Plugins register verified implementation availability, and pipelines
 * query it to select a supported path. Levels distinguish unavailable, fallback, partial and complete
 * native support without embedding vendor-specific rendering algorithms in the loader.
 */
public enum CapabilityLevel {
    /** Unavailable. */
    UNSUPPORTED(0, "Unsupported"),
    /** Usable through a generic fallback such as DP4a or compute, with possible quality or performance costs. */
    FALLBACK(1, "Fallback"),
    /** Hardware acceleration with only some features available. */
    PARTIAL(2, "Partial support"),
    /** Integrated through the vendor's native SDK. */
    NATIVE(3, "Native SDK"),
    /** Native SDK with all optional features available. */
    FULL(4, "Full features");

    private final int weight;
    private final String displayName;

    CapabilityLevel(int weight, String displayName) {
        this.weight = weight;
        this.displayName = displayName;
    }

    public int weight() {
        return weight;
    }

    public String displayName() {
        return tr(displayName);
    }

    /** Whether this meets the required level. */
    public boolean atLeast(CapabilityLevel other) {
        return other != null && this.weight >= other.weight;
    }

    public boolean isUsable() {
        return this != UNSUPPORTED;
    }

    /** Return the lower level for combined requirements. */
    public CapabilityLevel min(CapabilityLevel other) {
        return other == null || this.weight <= other.weight ? this : other;
    }

    /** Return the higher level. */
    public CapabilityLevel max(CapabilityLevel other) {
        return other == null || this.weight >= other.weight ? this : other;
    }

    /** Map boolean availability to NATIVE or UNSUPPORTED. */
    public static CapabilityLevel of(boolean available) {
        return available ? NATIVE : UNSUPPORTED;
    }

    public static CapabilityLevel parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        for (CapabilityLevel l : values()) {
            if (l.name().equalsIgnoreCase(raw.trim())) {
                return l;
            }
        }
        throw new IllegalArgumentException(tr("Unknown capability level: ") + raw);
    }

    public static CapabilityLevel tryParse(String raw, CapabilityLevel fallback) {
        if (raw == null) {
            return fallback;
        }
        for (CapabilityLevel l : values()) {
            if (l.name().equalsIgnoreCase(raw.trim())) {
                return l;
            }
        }
        return fallback;
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT) + "(" + displayName() + ")";
    }
}
