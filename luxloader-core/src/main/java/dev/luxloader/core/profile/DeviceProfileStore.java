package dev.luxloader.core.profile;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.core.util.SimpleJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persistent device profiles record capabilities and failed paths across runs. Keys include
 * vendor/device/driver/API versions so driver-specific behavior is not merged. Malformed or unwritable
 * profiles only produce warnings and fall back to missing data; diagnostics must not prevent startup.
 */
public final class DeviceProfileStore {

    /** Schema version for future migrations. */
    private static final int FORMAT_VERSION = 1;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Path file;

    /** Profiles indexed by keyOf, preserving stable order. */
    private final Map<String, Profile> profiles = new LinkedHashMap<>();

    /** Current run's profile key, null before device attachment. */
    private String currentKey;

    /**
     * Issue keys already counted this run. Persistent per-frame failures should count affected runs, not
     * meaningless frame totals.
     */
    private final Set<String> notedThisRun = new LinkedHashSet<>();

    /** Profile read/write warnings for diagnostics. */
    private final List<String> warnings = new ArrayList<>();

    public DeviceProfileStore(Path file) {
        this.file = file;
        load();
    }

    // Profile keys.

    /**
     * Keys by vendor/device/driver/API version, excluding unstable display names. If both vendor and
     * device IDs are zero, fall back to the device name to avoid merging unidentified machines into one
     * profile.
     */
    public static String keyOf(GpuCapabilities caps) {
        if (caps == null) {
            return "unknown";
        }
        if (caps.vendorId() == 0 && caps.deviceId() == 0) {
            // Keep unidentified devices separate from positively identified profiles.
            return "unidentified:" + caps.deviceName() + ":driver" + caps.driverVersion();
        }
        return String.format(java.util.Locale.ROOT, "0x%04X:0x%04X:driver%d:vk%s",
                caps.vendorId(), caps.deviceId(), caps.driverVersion(), caps.apiVersionString());
    }

    // Profile updates.

    /**
     * Records one device observation per run and increments runCount.
     * @return active profile
     */
    public synchronized Profile record(GpuCapabilities caps) {
        String key = keyOf(caps);
        String now = now();
        Profile existing = profiles.get(key);
        Profile updated = new Profile(
                key,
                caps.deviceName(),
                caps.vendor().displayName(),
                caps.vendorId(),
                caps.deviceId(),
                caps.driverVersion(),
                caps.driverName(),
                caps.apiVersionString(),
                caps.deviceMemoryGiB(),
                caps.extensions().size(),
                existing == null ? now : existing.firstSeen(),
                now,
                (existing == null ? 0 : existing.runCount()) + 1,
                existing == null ? Map.of() : existing.capabilities(),
                existing == null ? Map.of() : existing.issues());
        profiles.put(key, updated);
        currentKey = key;
        notedThisRun.clear();
        return updated;
    }

    /** Updates the current capability snapshot. */
    public synchronized void capabilities(Map<String, String> snapshot) {
        Profile p = currentProfile();
        if (p == null || snapshot == null) {
            return;
        }
        profiles.put(p.key(), p.withCapabilities(snapshot));
    }

    /**
     * Records a failed path under a stable machine-readable key, e.g. compute_pipeline_null_handle.
     * Human-readable notes may change; the latest replaces the previous note.
     */
    public synchronized void noteIssue(String key, String note) {
        Profile p = currentProfile();
        if (p == null || key == null || key.isBlank()) {
            return;
        }
        String now = now();
        Issue existing = p.issues().get(key);
        Issue updated = existing == null
                ? new Issue(key, 1, now, now, note == null ? "" : note)
                : new Issue(key, existing.count() + 1, existing.firstSeen(), now,
                note == null || note.isBlank() ? existing.note() : note);
        Map<String, Issue> issues = new LinkedHashMap<>(p.issues());
        issues.put(key, updated);
        profiles.put(p.key(), p.withIssues(issues));
    }

    /** Records an issue at most once per run, suitable for conditions that persist every frame. */
    public synchronized void noteIssueOnce(String key, String note) {
        if (key == null || key.isBlank() || !notedThisRun.add(key)) {
            return;
        }
        noteIssue(key, note);
    }

    // Profile queries.

    /** Current profile, null before device attachment. */
    public synchronized Profile currentProfile() {
        return currentKey == null ? null : profiles.get(currentKey);
    }

    /** Current run's profile key, null before device attachment. */
    public synchronized String currentKey() {
        return currentKey;
    }

    /** Looks up a profile key, returning empty if absent. */
    public synchronized Optional<Profile> find(String key) {
        return Optional.ofNullable(profiles.get(key));
    }

    /** All profiles, including historical devices. */
    public synchronized Collection<Profile> all() {
        return List.copyOf(profiles.values());
    }

    /** Profile count. */
    public synchronized int size() {
        return profiles.size();
    }

    /** Accumulated read/write warnings. */
    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    // Persistence.

    /**
     * Saves without throwing; failures become warnings.
     * @return whether writing succeeded
     */
    public synchronized boolean save() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", FORMAT_VERSION);
            Map<String, Object> entries = new LinkedHashMap<>();
            for (Profile p : profiles.values()) {
                entries.put(p.key(), toJson(p));
            }
            root.put("profiles", entries);
            SimpleJson.writeFileAtomic(file, root, 2);
            return true;
        } catch (IOException | RuntimeException e) {
            // Losing diagnostic history must not interrupt the game.
            warn(tr("Failed to write device profile (game continues): ") + e);
            return false;
        }
    }

    /**
     * Formats the current device and known issues for this run's report. Historical devices remain in JSON
     * instead of obscuring the current evidence.
     */
    public synchronized String describeCurrent() {
        Profile p = currentProfile();
        StringBuilder sb = new StringBuilder();
        if (p == null) {
            sb.append(tr("  (No device attached this run; no profile)")).append(System.lineSeparator());
        } else {
            sb.append(tr("  Profile key: ")).append(p.key()).append(System.lineSeparator());
            sb.append(tr("  Device: ")).append(p.deviceName())
                    .append("（").append(p.vendor()).append("，")
                    .append(String.format(java.util.Locale.ROOT, "0x%04X", p.deviceId())).append("）")
                    .append(System.lineSeparator());
            sb.append(tr("  Driver: ")).append(p.driverVersion())
                    .append(p.driver().isBlank() ? "" : "（" + p.driver() + "）")
                    .append("  Vulkan ").append(p.apiVersion())
                    .append(tr("  VRAM ")).append(p.vram())
                    .append(tr("  Extensions ")).append(p.extensionCount()).append(tr("DeviceProfileStore.f9d529eacd", " entries"))
                    .append(System.lineSeparator());
            sb.append(tr("  Seen: ")).append(p.runCount()).append(tr(" times"))
                    .append(tr("  First ")).append(p.firstSeen())
                    .append(tr("  Current ")).append(p.lastSeen())
                    .append(System.lineSeparator());

            if (p.issues().isEmpty()) {
                sb.append(tr("  Known issues: (none)")).append(System.lineSeparator());
            } else {
                sb.append(tr("  Known issues (accumulated across runs):"))
                        .append(System.lineSeparator());
                for (Issue issue : p.issues().values()) {
                    sb.append("    ").append(issue.key())
                            .append("  ×").append(issue.count())
                            .append(tr("  Recent ")).append(issue.lastSeen())
                            .append(System.lineSeparator());
                    if (!issue.note().isBlank()) {
                        sb.append("        ").append(issue.note()).append(System.lineSeparator());
                    }
                }
            }
        }
        if (!warnings.isEmpty()) {
            sb.append(tr("  Profile I/O warnings:")).append(System.lineSeparator());
            for (String w : warnings) {
                sb.append("    ").append(w).append(System.lineSeparator());
            }
        }
        if (profiles.size() > 1) {
            sb.append(tr("  Historical device profiles: ")).append(profiles.size()).append(tr(" (separate profiles for different drivers/devices)"))
                    .append(System.lineSeparator());
        }
        return sb.toString();
    }

    // Serialization.

    private static Map<String, Object> toJson(Profile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceName", p.deviceName());
        m.put("vendor", p.vendor());
        m.put("vendorId", p.vendorId());
        m.put("deviceId", p.deviceId());
        m.put("driverVersion", p.driverVersion());
        m.put("driver", p.driver());
        m.put("apiVersion", p.apiVersion());
        m.put("vram", p.vram());
        m.put("extensions", p.extensionCount());
        m.put("firstSeen", p.firstSeen());
        m.put("lastSeen", p.lastSeen());
        m.put("runCount", p.runCount());
        m.put("capabilities", new LinkedHashMap<>(p.capabilities()));
        Map<String, Object> issues = new LinkedHashMap<>();
        for (Issue i : p.issues().values()) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("count", i.count());
            im.put("firstSeen", i.firstSeen());
            im.put("lastSeen", i.lastSeen());
            im.put("note", i.note());
            issues.put(i.key(), im);
        }
        m.put("issues", issues);
        return m;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        Map<String, Object> root;
        try {
            root = SimpleJson.readFile(file);
        } catch (IOException | RuntimeException e) {
            // Preserve corrupt input as a renamed backup before rebuilding, retaining diagnostic evidence.
            quarantine(e);
            return;
        }
        try {
            Object rawProfiles = root.get("profiles");
            if (!(rawProfiles instanceof Map<?, ?> entries)) {
                return;
            }
            for (Map.Entry<?, ?> e : entries.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (e.getValue() instanceof Map<?, ?> pm) {
                    Profile p = fromJson(key, pm);
                    if (p != null) {
                        profiles.put(key, p);
                    }
                }
            }
        } catch (RuntimeException e) {
            // Keep successfully parsed entries if later parsing fails.
            warn(tr("Skipped unparseable device profile entries: ") + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Profile fromJson(String key, Map<?, ?> m) {
        try {
            Map<String, String> caps = new LinkedHashMap<>();
            if (m.get("capabilities") instanceof Map<?, ?> cm) {
                for (Map.Entry<?, ?> e : cm.entrySet()) {
                    caps.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            Map<String, Issue> issues = new LinkedHashMap<>();
            if (m.get("issues") instanceof Map<?, ?> im) {
                for (Map.Entry<?, ?> e : im.entrySet()) {
                    String ik = String.valueOf(e.getKey());
                    if (e.getValue() instanceof Map<?, ?> iv) {
                        issues.put(ik, new Issue(ik,
                                asInt(iv.get("count"), 1),
                                asString(iv.get("firstSeen")),
                                asString(iv.get("lastSeen")),
                                asString(iv.get("note"))));
                    }
                }
            }
            return new Profile(key,
                    asString(m.get("deviceName")),
                    asString(m.get("vendor")),
                    asInt(m.get("vendorId"), 0),
                    asInt(m.get("deviceId"), 0),
                    asInt(m.get("driverVersion"), 0),
                    asString(m.get("driver")),
                    asString(m.get("apiVersion")),
                    asString(m.get("vram")),
                    asInt(m.get("extensions"), 0),
                    asString(m.get("firstSeen")),
                    asString(m.get("lastSeen")),
                    asInt(m.get("runCount"), 0),
                    caps, issues);
        } catch (RuntimeException e) {
            // Skip malformed individual profiles without losing the whole collection.
            return null;
        }
    }

    /**
     * Tolerant numeric parsing accepts Long, Integer and manually edited numeric strings; unrecognized
     * values use the fallback.
     */
    private static int asInt(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private void quarantine(Exception cause) {
        warn(tr("Device profile could not be parsed; rebuilt: ") + cause);
        try {
            Path backup = file.resolveSibling(file.getFileName()
                    + ".corrupt-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            warn(tr("Malformed profile backed up to ") + backup);
        } catch (IOException e) {
            // Backup failure is nonfatal; save() can still atomically replace the file.
            warn(tr("Failed to back up malformed profile: ") + e);
        }
        profiles.clear();
    }

    private void warn(String message) {
        warnings.add(message);
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    // Data structures.

    /**
     * Known issue.
     * @param key stable cross-run identifier
     * @param count observations, or affected runs for per-frame conditions
     * @param firstSeen first occurrence
     * @param lastSeen latest occurrence
     * @param note human-readable details
     */
    public record Issue(String key, int count, String firstSeen, String lastSeen, String note) {
    }

    /**
     * Device profile.
     * @param capabilities capability ID to level, excluding verbose ext.* entries so useful summaries
     * remain visible
     */
    public record Profile(
            String key,
            String deviceName,
            String vendor,
            int vendorId,
            int deviceId,
            int driverVersion,
            String driver,
            String apiVersion,
            String vram,
            int extensionCount,
            String firstSeen,
            String lastSeen,
            int runCount,
            Map<String, String> capabilities,
            Map<String, Issue> issues) {

        public Profile {
            capabilities = Map.copyOf(capabilities);
            issues = Map.copyOf(issues);
        }

        Profile withCapabilities(Map<String, String> snapshot) {
            return new Profile(key, deviceName, vendor, vendorId, deviceId, driverVersion, driver,
                    apiVersion, vram, extensionCount, firstSeen, lastSeen, runCount,
                    snapshot, issues);
        }

        Profile withIssues(Map<String, Issue> updated) {
            return new Profile(key, deviceName, vendor, vendorId, deviceId, driverVersion, driver,
                    apiVersion, vram, extensionCount, firstSeen, lastSeen, runCount,
                    capabilities, updated);
        }
    }
}
