package dev.luxloader.core.capability;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.capability.CapabilityRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concurrent capability registry with immutable records and lock-free reads. Later registrations
 * replace earlier ones only at an equal or higher support level. Explicitly revoke a capability to
 * disable it; a narrower plugin use case must not silently downgrade a valid device probe.
 *
 * <p>The registry keeps two independent sources per ID:
 *
 * <ul>
 *   <li>an <em>independent registration</em> ({@link #register}/{@link #forceRegister}) for device,
 *       host and adapter facts, and
 *   <li><em>plugin claims</em> ({@link #claim}/{@link #releaseClaims}) that remember who declared
 *       what.
 * </ul>
 *
 * <p>The effective level is the highest one that is still valid. This is what makes rollback safe:
 * releasing the claims of one owner drops only that owner's submissions, and the next best source
 * becomes effective again instead of the capability collapsing to UNSUPPORTED. A claim is recorded
 * only after it was accepted, so a rejected submission never becomes authority to revoke a valid
 * provider.
 */
public final class CapabilityRegistryImpl implements CapabilityRegistry {

    /** Independent registration, kept apart from plugin claims so a claim rollback cannot touch it. */
    private final Map<String, CapabilityDescriptor> independent = new ConcurrentHashMap<>();

    /** Plugin claims per capability ID, with claiming owner and submission order. */
    private final Map<String, List<Claim>> claims = new ConcurrentHashMap<>();

    private final Map<String, Boolean> extensionSupport = new ConcurrentHashMap<>();
    private final List<String> revocationLog = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Effective descriptors together with the source version they were built from. Publishing one
     * immutable record keeps data and stamp from ever disagreeing. The compare-and-set that installs
     * it only replaces the snapshot the rebuild started from, so a rebuild that sampled sources before
     * a release cannot mark its older table as current afterwards.
     */
    private record EffectiveSnapshot(Map<String, CapabilityDescriptor> descriptors, long version) {
    }

    /** Current immutable snapshot; empty and stale until the first rebuild. */
    private final java.util.concurrent.atomic.AtomicReference<EffectiveSnapshot> snapshot =
            new java.util.concurrent.atomic.AtomicReference<>(
                    new EffectiveSnapshot(Map.of(), -1L));

    /**
     * Bumped on every source change. A reader that observes a change rebuilds; a snapshot with an
     * unchanged stamp is already correct, so a rebuild does not need a lock.
     */
    private final java.util.concurrent.atomic.AtomicLong version =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Test-only seam invoked after a rebuild and before publication. It lets a regression test order
     * the publication of two rebuilds deterministically instead of relying on scheduler timing,
     * sleeps or source line numbers. Production leaves it null, so one reference check per rebuild is
     * the entire cost. Tests must clear it when they finish.
     */
    private static volatile Runnable publishProbe;

    /** Installs or clears the publication probe; pass null to remove it. */
    public static void setPublishProbe(Runnable probe) {
        publishProbe = probe;
    }

    /** Source version the published snapshot was built from; -1 before the first rebuild. */
    long publishedVersion() {
        return snapshot.get().version();
    }

    /** Current source version. */
    long sourceVersion() {
        return version.get();
    }

    /** One claim submission: which owner declared it, and what it declared. */
    private record Claim(String owner, CapabilityDescriptor descriptor) {
    }

    @Override
    public CapabilityLevel level(String id) {
        CapabilityDescriptor descriptor = effectiveDescriptor(id);
        return descriptor == null ? CapabilityLevel.UNSUPPORTED : descriptor.level();
    }

    @Override
    public boolean isRegistered(String id) {
        return effectiveDescriptor(id) != null;
    }

    @Override
    public Optional<CapabilityDescriptor> find(String id) {
        return Optional.ofNullable(effectiveDescriptor(id));
    }

    @Override
    public List<CapabilityDescriptor> all() {
        refreshEffective();
        List<CapabilityDescriptor> list = new ArrayList<>(snapshot.get().descriptors().values());
        list.sort(Comparator.comparing(CapabilityDescriptor::id));
        return List.copyOf(list);
    }

    @Override
    public List<CapabilityDescriptor> withPrefix(String prefix) {
        String p = prefix == null ? "" : prefix;
        return all().stream().filter(d -> d.id().startsWith(p)).toList();
    }

    @Override
    public boolean supportsExtension(String extension) {
        Boolean direct = extensionSupport.get(extension);
        if (direct != null) {
            return direct;
        }
        // Fallback: probing registers each extension under ext.<name>.
        return level("ext." + extension).isUsable();
    }

    // Internal registration API.

    /**
     * Register an independent capability, replacing or refreshing the stored one at an equal or
     * higher level or when no entry exists.
     * @return whether the value became the stored registration
     */
    public boolean register(CapabilityDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        CapabilityDescriptor stamped = descriptor.verifiedAtNanos() == 0L ? descriptor.verifiedNow() : descriptor;
        boolean[] accepted = new boolean[1];
        independent.compute(stamped.id(), (id, existing) -> {
            if (existing == null || stamped.level().weight() >= existing.level().weight()) {
                accepted[0] = true;
                return stamped;
            }
            return existing;
        });
        if (accepted[0]) {
            version.incrementAndGet();
        }
        return accepted[0];
    }

    /** Force an independent registration, replacing any stored value. */
    public void forceRegister(CapabilityDescriptor descriptor) {
        if (descriptor != null) {
            independent.put(descriptor.id(), descriptor.verifiedAtNanos() == 0L
                    ? descriptor.verifiedNow() : descriptor);
            version.incrementAndGet();
        }
    }

    /**
     * Submit a capability claim on behalf of {@code owner}, typically a loaded plugin. The claim is
     * recorded only when it becomes the stored claim for its ID; a rejected submission leaves no
     * trace and therefore conveys no cleanup authority.
     * @param owner claiming plugin or component ID
     * @param descriptor claimed capability
     * @return whether the claim was accepted as the owner's current claim for this ID
     */
    public boolean claim(String owner, CapabilityDescriptor descriptor) {
        if (owner == null || owner.isBlank() || descriptor == null) {
            return false;
        }
        CapabilityDescriptor stamped = descriptor.verifiedAtNanos() == 0L ? descriptor.verifiedNow() : descriptor;
        boolean[] accepted = new boolean[1];
        claims.compute(stamped.id(), (id, existing) -> {
            List<Claim> current = existing == null ? List.of() : existing;
            int index = indexOfOwner(current, owner);
            if (index < 0) {
                accepted[0] = true;
                List<Claim> next = new ArrayList<>(current);
                next.add(new Claim(owner, stamped));
                return List.copyOf(next);
            }
            CapabilityDescriptor previous = current.get(index).descriptor();
            if (stamped.level().weight() < previous.level().weight()) {
                return current;
            }
            accepted[0] = true;
            List<Claim> next = new ArrayList<>(current);
            next.set(index, new Claim(owner, stamped));
            return List.copyOf(next);
        });
        if (accepted[0]) {
            version.incrementAndGet();
        }
        return accepted[0];
    }

    /**
     * Release every claim made by {@code owner}. Other owners keep theirs, and the effective value of
     * each affected ID is recomputed from the remaining claims and independent registrations, so a
     * provider that is still valid becomes effective again.
     * @param owner owner whose claims are released
     * @return capability IDs whose claims were released
     */
    public List<String> releaseClaims(String owner) {
        if (owner == null || owner.isBlank()) {
            return List.of();
        }
        List<String> released = new ArrayList<>();
        for (Map.Entry<String, List<Claim>> entry : claims.entrySet()) {
            List<Claim>[] result = new List[1];
            claims.compute(entry.getKey(), (id, existing) -> {
                if (existing == null || indexOfOwner(existing, owner) < 0) {
                    return existing;
                }
                List<Claim> next = new ArrayList<>(existing);
                next.removeIf(claim -> claim.owner().equals(owner));
                result[0] = next;
                return next.isEmpty() ? null : List.copyOf(next);
            });
            if (result[0] != null) {
                released.add(entry.getKey());
            }
        }
        if (!released.isEmpty()) {
            version.incrementAndGet();
        }
        return List.copyOf(released);
    }

    /** Claims per capability ID, for diagnostics. */
    public Map<String, List<String>> claimsByCapability() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        claims.forEach((id, list) -> out.put(id, list.stream().map(Claim::owner).toList()));
        return Map.copyOf(out);
    }

    /** All capabilities declared by one claim owner. */
    public List<CapabilityDescriptor> claimsOf(String owner) {
        if (owner == null || owner.isBlank()) {
            return List.of();
        }
        List<CapabilityDescriptor> out = new ArrayList<>();
        claims.values().forEach(list -> list.stream()
                .filter(claim -> claim.owner().equals(owner))
                .forEach(claim -> out.add(claim.descriptor())));
        out.sort(Comparator.comparing(CapabilityDescriptor::id));
        return List.copyOf(out);
    }

    private static int indexOfOwner(List<Claim> list, String owner) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).owner().equals(owner)) {
                return i;
            }
        }
        return -1;
    }

    /** Record extension support during device probing. */
    public void extension(String name, boolean supported) {
        extensionSupport.put(name, supported);
        forceRegister(new CapabilityDescriptor("ext." + name, CapabilityLevel.of(supported),
                name, "device-probe", supported ? tr("Available") : tr("Device does not expose this extension"), Map.of(), System.nanoTime()));
    }

    /** Register multiple extensions. */
    public void extensions(Set<String> available, Set<String> allKnown) {
        for (String name : allKnown) {
            extension(name, available.contains(name));
        }
    }

    /**
     * Revoke an independent capability and record the reason. Use for runtime failures such as native
     * initialization errors to avoid repeated attempts every frame; revocations appear in
     * diagnostics. Claims are not touched; use {@link #releaseClaims} to remove a claim owner's
     * declarations.
     */
    public void revoke(String id, String reason) {
        revocationLog.add(id + ": " + reason);
        forceRegister(CapabilityDescriptor.unsupported(id, "runtime-revoke", reason));
    }

    /** Revocation log for diagnostics. */
    public List<String> revocationLog() {
        return List.copyOf(revocationLog);
    }

    /** Registered extension count. */
    public int extensionCount() {
        return extensionSupport.size();
    }

    /** Clear during plugin reload. */
    public void clear() {
        independent.clear();
        claims.clear();
        extensionSupport.clear();
        revocationLog.clear();
        // Publish the empty table with the version that orders it, so a rebuild that started before
        // this clear cannot be accepted afterwards.
        snapshot.set(new EffectiveSnapshot(Map.of(), version.incrementAndGet()));
    }

    // Effective value resolution.

    /** The stored value for an ID: the best remaining claim, then an independent registration. */
    private CapabilityDescriptor effectiveDescriptor(String id) {
        if (id == null) {
            return null;
        }
        refreshEffective();
        return snapshot.get().descriptors().get(id);
    }

    /**
     * Rebuild the effective table when any source changed and publish it with its version as one
     * immutable record.
     *
     * <p>Data and stamp are published together, so a published table always belongs to the version it
     * carries. The compare-and-set only replaces the snapshot this rebuild started from, so a rebuild
     * that sampled sources before a release cannot mark its older table as current: it retries and
     * republishes from the newer sources, and the next read observes the released sources.
     */
    private void refreshEffective() {
        for (int attempt = 0; attempt < MAX_REBUILD_ATTEMPTS; attempt++) {
            EffectiveSnapshot current = snapshot.get();
            long seen = version.get();
            if (seen == current.version()) {
                return;
            }
            Map<String, CapabilityDescriptor> rebuilt = new LinkedHashMap<>();
            independent.forEach((id, descriptor) -> rebuilt.put(id, descriptor));
            for (Map.Entry<String, List<Claim>> entry : claims.entrySet()) {
                for (Claim claim : entry.getValue()) {
                    CapabilityDescriptor candidate = claim.descriptor();
                    CapabilityDescriptor existing = rebuilt.get(entry.getKey());
                    if (isBetter(candidate, existing)) {
                        rebuilt.put(entry.getKey(), candidate);
                    }
                }
            }
            Runnable probe = publishProbe;
            if (probe != null) {
                probe.run();
            }
            // Publish the table together with the version it was built from. The compare-and-set only
            // replaces the snapshot this rebuild started from, so a rebuild that sampled older sources
            // cannot mark its table as current: it retries and republishes from the newer sources.
            if (snapshot.compareAndSet(current, new EffectiveSnapshot(Map.copyOf(rebuilt), seen))) {
                return;
            }
        }
        // Contention only; the next read rebuilds instead of publishing a snapshot out of order.
    }

    /** Bounded retry for the lock-free publication loop. */
    private static final int MAX_REBUILD_ATTEMPTS = 8;

    /**
     * Highest support level wins. At the same level the more recent verification wins, so a later
     * provider's refresh is not silently discarded while an older source keeps the entry.
     */
    private static boolean isBetter(CapabilityDescriptor candidate, CapabilityDescriptor current) {
        if (current == null) {
            return true;
        }
        if (candidate.level().weight() != current.level().weight()) {
            return candidate.level().weight() > current.level().weight();
        }
        return candidate.verifiedAtNanos() >= current.verifiedAtNanos();
    }

    /** Format the capability table for diagnostic reports. */
    public String describeTable() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-46s %-12s %s%n", tr("Capability"), tr("Level"), tr("Details")));
        sb.append("-".repeat(100)).append(System.lineSeparator());
        for (CapabilityDescriptor d : all()) {
            if (d.id().startsWith("ext.")) {
                continue; // List extensions separately to keep the report readable.
            }
            sb.append(String.format("%-46s %-12s %s%n", d.id(), d.level().name(), d.detail()));
        }
        return sb.toString();
    }
}
