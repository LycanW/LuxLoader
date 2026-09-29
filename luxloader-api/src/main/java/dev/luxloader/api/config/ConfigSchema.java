package dev.luxloader.api.config;

import static dev.luxloader.api.i18n.Messages.tr;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Declarative plugin configuration: options specify keys, types, ranges and display metadata; the
 * loader handles files, validation, UI and update callbacks. Use ConfigOption factories and
 * ConfigSchema.builder(). Options marked restartRequired trigger close() followed by initialize();
 * other changes use onSettingsChanged.
 */
public interface ConfigSchema {

    /** All declared options. */
    List<ConfigOption<?>> options();

    /** Find an option by path. */
    Optional<ConfigOption<?>> find(String path);

    /** Options under a path prefix for grouped UI. */
    default List<ConfigOption<?>> under(String prefix) {
        String p = prefix == null || prefix.isBlank() ? "" : prefix + ".";
        return options().stream().filter(o -> o.path().startsWith(p)).toList();
    }

    /** UI group titles in order of first appearance. */
    default List<String> groups() {
        return options().stream().map(ConfigOption::group).distinct().toList();
    }

    /** Options in a group. */
    default List<ConfigOption<?>> inGroup(String group) {
        return options().stream().filter(o -> o.group().equals(group)).toList();
    }

    /** Write every default through the editor. Include comments and groups so generated files remain readable. */
    void writeDefaults(ConfigEditor editor);

    /**
     * Register a change listener.
     * @param path watched path, or * for any path
     * @param listener lightweight callback invoked on the render thread
     */
    void onChange(String path, Consumer<String> listener);

    /** Configuration schema version for migration; defaults to 1. */
    default int version() {
        return 1;
    }

    /**
     * Migrate files with an older schema version. Implementations may rename keys, add defaults or remove
     * obsolete keys; return whether changes were made.
     */
    default boolean migrate(int fromVersion, ConfigEditor editor) {
        return false;
    }

    /** Generate commented default files automatically; true by default. */
    default boolean generateComments() {
        return true;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Configuration schema builder. */
    final class Builder {
        private final List<ConfigOption<?>> options = new java.util.ArrayList<>();

        private Builder() {
        }

        /** Add a built option. */
        public Builder add(ConfigOption<?> option) {
            options.add(java.util.Objects.requireNonNull(option, "option"));
            return this;
        }

        /** Add multiple options. */
        public Builder add(ConfigOption<?>... options) {
            for (ConfigOption<?> o : options) {
                add(o);
            }
            return this;
        }

        /** Build the schema; duplicate paths throw to catch declaration mistakes. */
        public ConfigSchema build() {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (ConfigOption<?> o : options) {
                if (!seen.add(o.path())) {
                    throw new IllegalStateException(tr("Duplicate configuration path: ") + o.path());
                }
            }
            List<ConfigOption<?>> snapshot = List.copyOf(options);
            return new ConfigSchema() {
                private final java.util.Map<String, java.util.List<Consumer<String>>> listeners =
                        new java.util.concurrent.ConcurrentHashMap<>();

                @Override
                public List<ConfigOption<?>> options() {
                    return snapshot;
                }

                @Override
                public Optional<ConfigOption<?>> find(String path) {
                    return snapshot.stream().filter(o -> o.path().equals(path)).findFirst();
                }

                @Override
                public void writeDefaults(ConfigEditor editor) {
                    for (ConfigOption<?> o : snapshot) {
                        o.writeDefault(editor);
                    }
                }

                @Override
                public void onChange(String path, Consumer<String> listener) {
                    listeners.computeIfAbsent(path, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                            .add(listener);
                }

                @Override
                public void notifyChanged(String path) {
                    java.util.List<Consumer<String>> direct = listeners.get(path);
                    if (direct != null) {
                        direct.forEach(l -> l.accept(path));
                    }
                    java.util.List<Consumer<String>> all = listeners.get("*");
                    if (all != null) {
                        all.forEach(l -> l.accept(path));
                    }
                }
            };
        }
    }

    /** Notify listeners after configuration reload. The default does nothing for lightweight test implementations. */
    default void notifyChanged(String path) {
    }
}
