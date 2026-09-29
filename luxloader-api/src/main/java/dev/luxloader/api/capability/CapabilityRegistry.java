package dev.luxloader.api.capability;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Read-only capability registry for runtime fallback selection. Queries never throw for missing
 * entries; an unregistered capability is UNSUPPORTED. Use atLeast(id, minimum) or
 * selectBest(candidates, minimum) so missing probe plugins disable optional paths without crashing the
 * pipeline.
 */
public interface CapabilityRegistry {

    /** Return the support level; unregistered entries are UNSUPPORTED. */
    CapabilityLevel level(String id);

    /** Whether the capability meets the minimum level. */
    default boolean atLeast(String id, CapabilityLevel minimum) {
        return level(id).atLeast(minimum);
    }

    /** Whether the capability is usable (not UNSUPPORTED). */
    default boolean isUsable(String id) {
        return level(id).isUsable();
    }

    /** Whether registered, including explicitly unsupported entries. */
    boolean isRegistered(String id);

    /** Get the complete capability record. */
    Optional<CapabilityDescriptor> find(String id);

    /** All registered capabilities, sorted by identifier. */
    List<CapabilityDescriptor> all();

    /** Filter by prefix, e.g. graphics. or nvidia. */
    List<CapabilityDescriptor> withPrefix(String prefix);

    /** Only usable capabilities. */
    default List<CapabilityDescriptor> usable() {
        return all().stream().filter(CapabilityDescriptor::isUsable).toList();
    }

    /**
     * Read an integer attribute, such as the maximum frame generation multiplier.
     * @param fallback value when missing or unusable
     */
    default int intAttribute(String id, String attribute, int fallback) {
        return find(id).map(d -> d.intAttribute(attribute, fallback)).orElse(fallback);
    }

    /**
     * Select the first usable implementation in priority order.
     * @param candidateIds candidate identifiers from highest to lowest priority
     * @param minimum minimum acceptable support
     * @return matching identifier, or empty if none qualifies
     */
    default Optional<String> selectBest(List<String> candidateIds, CapabilityLevel minimum) {
        for (String id : candidateIds) {
            if (atLeast(id, minimum)) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /** Like selectBest, also returning the support level for quality selection. */
    default Optional<Selection> selectBestWithLevel(List<String> candidateIds, CapabilityLevel minimum) {
        for (String id : candidateIds) {
            CapabilityLevel l = level(id);
            if (l.atLeast(minimum)) {
                return Optional.of(new Selection(id, l));
            }
        }
        return Optional.empty();
    }

    /** Query ext.&lt;name&gt; when registered, otherwise fall back to the device capability snapshot. */
    boolean supportsExtension(String extension);

    /** Selected capability. */
    record Selection(String id, CapabilityLevel level) {
    }

    /** Reason an option is unavailable in the diagnostic UI. */
    record UnavailableReason(String id, String reason) {
    }

    /** Summarize unavailable capabilities and their reasons. */
    default List<UnavailableReason> unavailableReasons() {
        return all().stream()
                .filter(d -> !d.isUsable())
                .map(d -> new UnavailableReason(d.id(), d.detail().isEmpty() ? tr("Unsupported environment") : d.detail()))
                .toList();
    }

    /** Read a capability's maximum multiplier, typically for frame generation. */
    default OptionalInt maxMultiplier(String id) {
        Optional<CapabilityDescriptor> found = find(id);
        if (found.isEmpty() || !found.get().isUsable()) {
            return OptionalInt.empty();
        }
        CapabilityDescriptor descriptor = found.get();
        if (!descriptor.attributes().containsKey("maxMultiplier")) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(descriptor.intAttribute("maxMultiplier", 1));
    }
}
