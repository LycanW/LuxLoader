package dev.luxloader.api.pipeline;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.capability.CapabilityRegistry;
import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuVendor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


/**
 * Runtime prerequisites checked before pipeline instantiation, so unsupported devices fail with
 * diagnostics before native calls. Hard requirements reject loading; soft capability expectations warn
 * and let plugins decide fallback behavior.
 * @param minimumVulkanVersion minimum Vulkan version, e.g. 1.3; empty means unrestricted
 * @param requiredExtensions required device extensions
 * @param optionalExtensions optional extensions whose absence may select another code path
 * @param requiredFeatures required Vulkan feature field names, e.g. shaderInt64 or bufferDeviceAddress
 * @param requiredCapabilities capability IDs and minimum levels
 * @param allowedVendors permitted vendors; empty means unrestricted
 * @param blockedVendors explicitly excluded vendors
 * @param requiresNativeBridge whether native loading is required
 * @param maxVramBytes maximum acceptable memory requirement; zero means unrestricted
 * @param minVramBytes minimum VRAM requirement
 */
public record Requirements(
        String minimumVulkanVersion,
        Set<String> requiredExtensions,
        Set<String> optionalExtensions,
        Set<String> requiredFeatures,
        java.util.Map<String, CapabilityLevel> requiredCapabilities,
        java.util.Map<String, CapabilityLevel> preferredCapabilities,
        Set<GpuVendor> allowedVendors,
        Set<GpuVendor> blockedVendors,
        boolean requiresNativeBridge,
        long minVramBytes,
        long maxVramBytes) {

    /** Requirement validation result. */
    public record Verdict(boolean passed, List<String> failures, List<String> warnings) {

        public Verdict {
            failures = failures == null ? List.of() : List.copyOf(failures);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public static Verdict pass() {
            return new Verdict(true, List.of(), List.of());
        }

        public static Verdict fail(String reason) {
            return new Verdict(false, List.of(reason), List.of());
        }

        public Verdict withWarning(String warning) {
            List<String> w = new java.util.ArrayList<>(warnings);
            w.add(warning);
            return new Verdict(passed, failures, w);
        }

        /** Readable failure reasons for logs and configuration UI. */
        public String explain() {
            if (passed && warnings.isEmpty()) {
                return tr("All requirements satisfied");
            }
            StringBuilder sb = new StringBuilder();
            if (!failures.isEmpty()) {
                sb.append(tr("Unmet: ")).append(String.join("；", failures));
            }
            if (!warnings.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(tr("Note: ")).append(String.join("；", warnings));
            }
            return sb.toString();
        }
    }

    public Requirements {
        minimumVulkanVersion = minimumVulkanVersion == null ? "" : minimumVulkanVersion.trim();
        requiredExtensions = requiredExtensions == null ? Set.of() : Set.copyOf(requiredExtensions);
        optionalExtensions = optionalExtensions == null ? Set.of() : Set.copyOf(optionalExtensions);
        requiredFeatures = requiredFeatures == null ? Set.of() : Set.copyOf(requiredFeatures);
        requiredCapabilities = requiredCapabilities == null ? java.util.Map.of() : java.util.Map.copyOf(requiredCapabilities);
        preferredCapabilities = preferredCapabilities == null ? java.util.Map.of() : java.util.Map.copyOf(preferredCapabilities);
        allowedVendors = allowedVendors == null ? Set.of() : Set.copyOf(allowedVendors);
        blockedVendors = blockedVendors == null ? Set.of() : Set.copyOf(blockedVendors);
        if (minVramBytes < 0 || maxVramBytes < 0) {
            throw new IllegalArgumentException(tr("VRAM constraints must not be negative"));
        }
    }

    /** No requirements. */
    public static Requirements none() {
        return new Requirements("", Set.of(), Set.of(), Set.of(), java.util.Map.of(),
                java.util.Map.of(), Set.of(), Set.of(), false, 0L, 0L);
    }

    /**
     * Validates against device capabilities and the registry.
     * @param caps device snapshot
     * @param capabilities capability registry; null skips soft capability checks
     * @param nativeAvailable whether native loading is available
     */
    public Verdict verify(GpuCapabilities caps, CapabilityRegistry capabilities, boolean nativeAvailable) {
        Objects.requireNonNull(caps, "caps");
        List<String> failures = new java.util.ArrayList<>();
        List<String> warnings = new java.util.ArrayList<>();

        if (!minimumVulkanVersion.isEmpty()) {
            String[] parts = minimumVulkanVersion.split("\\.");
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                if (!caps.apiAtLeast(major, minor)) {
                    failures.add(tr("Requires Vulkan ") + minimumVulkanVersion + tr(", current ") + caps.apiVersionString());
                }
            } catch (NumberFormatException e) {
                warnings.add(tr("Cannot parse minimumVulkanVersion='") + minimumVulkanVersion + tr("'; validation skipped"));
            }
        }

        for (String ext : requiredExtensions) {
            if (!caps.supports(ext)) {
                failures.add(tr("Missing device extension ") + ext);
            }
        }

        for (String feature : requiredFeatures) {
            // Feature availability comes from the capability registry; the device snapshot covers extensions only. Missing feature registrations must be reported as unverifiable. The previous isRegistered && !isUsable guard silently accepted unregistered feature.* keys and made requireFeatures ineffective.
            if (capabilities == null) {
                warnings.add(tr("Cannot validate device feature ") + feature + tr(": capability registry is empty"));
            } else if (!capabilities.isRegistered("feature." + feature)) {
                warnings.add(tr("Cannot validate device feature ") + feature
                        + tr(": it is not registered as a capability (key feature.") + feature + tr(" is missing), ")
                        + tr("so this requirement is not enforced"));
            } else if (!capabilities.isUsable("feature." + feature)) {
                failures.add(tr("Missing device feature ") + feature);
            }
        }

        if (capabilities != null) {
            for (var e : requiredCapabilities.entrySet()) {
                CapabilityLevel actual = capabilities.level(e.getKey());
                if (!actual.atLeast(e.getValue())) {
                    String detail = capabilities.find(e.getKey())
                            .map(d -> d.detail().isEmpty() ? "" : "（" + d.detail() + "）")
                            .orElse(tr(" (unregistered)"));
                    failures.add(tr("Capability ") + e.getKey() + tr(" requires ") + e.getValue() + tr(", actual ") + actual + detail);
                }
            }
        }

        // Soft requirements produce warnings rather than load failures. Plugins choose whether to fall back after inspecting capabilities. Using a hard requireCapability for optional features would reject the very devices that need the fallback.
        if (capabilities != null) {
            for (var e : preferredCapabilities.entrySet()) {
                CapabilityLevel actual = capabilities.level(e.getKey());
                if (!actual.atLeast(e.getValue())) {
                    String detail = capabilities.find(e.getKey())
                            .map(d -> d.detail().isEmpty() ? "" : "（" + d.detail() + "）")
                            .orElse(tr(" (unregistered)"));
                    warnings.add(tr("Capability ") + e.getKey() + tr(" only reaches ") + actual
                            + tr(" (preferred ") + e.getValue() + "）" + detail + tr("; pipeline will use a fallback"));
                }
            }
        }

        if (!allowedVendors.isEmpty() && !allowedVendors.contains(caps.vendor())) {
            failures.add(tr("Only supports ") + allowedVendors + tr(", current device is ") + caps.vendor().displayName());
        }
        if (blockedVendors.contains(caps.vendor())) {
            failures.add(tr("Disabled on ") + caps.vendor().displayName() + tr(" (known issue)"));
        }

        if (requiresNativeBridge && !nativeAvailable) {
            failures.add(tr("Native library loading (FFM/JNI) is required but unavailable"));
        }

        if (minVramBytes > 0 && caps.deviceMemoryBytes() > 0 && caps.deviceMemoryBytes() < minVramBytes) {
            failures.add(tr("Requires at least ") + (minVramBytes >> 20) + tr(" MiB VRAM, available ") + (caps.deviceMemoryBytes() >> 20) + " MiB");
        }
        if (maxVramBytes > 0 && caps.deviceMemoryBytes() > maxVramBytes) {
            warnings.add(tr("VRAM ") + (caps.deviceMemoryBytes() >> 20) + tr(" MiB exceeds the expected maximum; compatibility may be untested"));
        }

        return new Verdict(failures.isEmpty(), failures, warnings);
    }

    /** Missing extensions for actionable diagnostics. */
    public List<String> missingExtensions(GpuCapabilities caps) {
        return requiredExtensions.stream().filter(e -> !caps.supports(e)).sorted().toList();
    }

    public Builder toBuilder() {
        return new Builder()
                .minimumVulkanVersion(minimumVulkanVersion)
                .requireExtensions(requiredExtensions.toArray(new String[0]))
                .optionalExtensions(optionalExtensions.toArray(new String[0]))
                .requireFeatures(requiredFeatures.toArray(new String[0]))
                .requireCapabilities(requiredCapabilities)
                .allowVendors(allowedVendors.toArray(new GpuVendor[0]))
                .blockVendors(blockedVendors.toArray(new GpuVendor[0]))
                .requiresNativeBridge(requiresNativeBridge)
                .vram(minVramBytes, maxVramBytes);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Read the required level for a capability. */
    public Optional<CapabilityLevel> capabilityRequirement(String id) {
        return Optional.ofNullable(requiredCapabilities.get(id));
    }

    /** Requirements builder. */
    public static final class Builder {
        private String minimumVulkanVersion = "";
        private final Set<String> extensions = new java.util.LinkedHashSet<>();
        private final Set<String> optionalExtensions = new java.util.LinkedHashSet<>();
        private final Set<String> features = new java.util.LinkedHashSet<>();
        private final java.util.Map<String, CapabilityLevel> capabilities = new java.util.LinkedHashMap<>();
        private final Set<GpuVendor> allowed = new java.util.LinkedHashSet<>();
        private final Set<GpuVendor> blocked = new java.util.LinkedHashSet<>();
        private boolean nativeBridge;
        private long minVram;
        private long maxVram;

        public Builder minimumVulkanVersion(String version) {
            this.minimumVulkanVersion = version;
            return this;
        }

        public Builder requireExtensions(String... names) {
            for (String n : names) {
                extensions.add(n);
            }
            return this;
        }

        public Builder optionalExtensions(String... names) {
            for (String n : names) {
                optionalExtensions.add(n);
            }
            return this;
        }

        public Builder requireFeatures(String... names) {
            for (String n : names) {
                features.add(n);
            }
            return this;
        }

        /** Require a capability at a minimum level, e.g. requireCapability("graphics.ray_query", NATIVE). */
        public Builder requireCapability(String id, CapabilityLevel minimum) {
            capabilities.put(id, minimum);
            return this;
        }

        public Builder requireCapabilities(java.util.Map<String, CapabilityLevel> map) {
            capabilities.putAll(map);
            return this;
        }

        /** Preferred capability: warn when missing without preventing loading. */
        private final java.util.Map<String, CapabilityLevel> preferred = new java.util.LinkedHashMap<>();

        /**
         * Declares a soft minimum capability expectation. An unmet expectation warns without rejecting the
         * pipeline, allowing the plugin to choose a fallback. Use requireCapability only for hard requirements
         * whose absence must prevent loading.
         */
        public Builder preferCapability(String id, CapabilityLevel preferredLevel) {
            preferred.put(id, preferredLevel);
            return this;
        }

        /** Declare multiple preferred capabilities. */
        public Builder preferCapabilities(java.util.Map<String, CapabilityLevel> map) {
            preferred.putAll(map);
            return this;
        }

        public Builder allowVendors(GpuVendor... vendors) {
            for (GpuVendor v : vendors) {
                allowed.add(v);
            }
            return this;
        }

        public Builder blockVendors(GpuVendor... vendors) {
            for (GpuVendor v : vendors) {
                blocked.add(v);
            }
            return this;
        }

        public Builder requiresNativeBridge(boolean required) {
            this.nativeBridge = required;
            return this;
        }

        public Builder vram(long minBytes, long maxBytes) {
            this.minVram = minBytes;
            this.maxVram = maxBytes;
            return this;
        }

        public Requirements build() {
            return new Requirements(minimumVulkanVersion, extensions, optionalExtensions, features,
                    capabilities, preferred, allowed, blocked, nativeBridge, minVram, maxVram);
        }
    }
}
