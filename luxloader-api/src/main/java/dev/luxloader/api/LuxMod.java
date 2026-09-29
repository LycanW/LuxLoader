package dev.luxloader.api;

import java.util.List;
import java.util.Objects;

/**
 * Plugin metadata read by the host during discovery for conflict detection, UI and logs.
 * @param id unique identifier
 * @param name display name
 * @param version semantic version, e.g. 1.0.0
 * @param authors author list, possibly empty
 * @param description short description
 * @param mcConstraint Maven version range, e.g. [26.3,); empty means unrestricted; incompatible
 * plugins are rejected with diagnostics
 * @param license SPDX license identifier
 */
public record LuxMod(
        GpuId id,
        String name,
        String version,
        List<String> authors,
        String description,
        String mcConstraint,
        String license) {

    public LuxMod {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        authors = authors == null ? List.of() : List.copyOf(authors);
        description = description == null ? "" : description;
        mcConstraint = mcConstraint == null ? "" : mcConstraint;
        license = license == null ? "" : license;
    }

    public static Builder builder(GpuId id, String name, String version) {
        return new Builder(id, name, version);
    }

    /** Plugin metadata builder. */
    public static final class Builder {
        private final GpuId id;
        private final String name;
        private final String version;
        private List<String> authors = List.of();
        private String description = "";
        private String mcConstraint = "";
        private String license = "";

        private Builder(GpuId id, String name, String version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }

        public Builder authors(String... authors) {
            this.authors = List.of(authors);
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** For example, {@code [26.3,)} accepts Minecraft 26.3 and later. */
        public Builder mcConstraint(String mcConstraint) {
            this.mcConstraint = mcConstraint;
            return this;
        }

        public Builder license(String license) {
            this.license = license;
            return this;
        }

        public LuxMod build() {
            return new LuxMod(id, name, version, authors, description, mcConstraint, license);
        }
    }
}
