package dev.luxloader.api.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Declarative configuration option. The host generates files with comments and groups, renders
 * controls, validates values and reports invalid entries. restartRequired marks changes that require
 * pipeline reconstruction.
 * @param <T> value type
 */
public abstract sealed class ConfigOption<T>
        permits ConfigOption.Bool, ConfigOption.Numeric, ConfigOption.Text, ConfigOption.EnumOption, ConfigOption.ListOption {

    private final String path;
    private final String displayName;
    private final String description;
    private final T defaultValue;
    private final List<T> legacyDefaults;
    private final boolean restartRequired;
    private final String group;
    private final Supplier<Boolean> visibleWhen;

    private ConfigOption(String path, String displayName, String description, T defaultValue,
                         List<T> legacyDefaults, boolean restartRequired, String group,
                         Supplier<Boolean> visibleWhen) {
        this.path = ConfigView.requireValidPath(path);
        this.displayName = Objects.requireNonNullElse(displayName, path);
        this.description = Objects.requireNonNullElse(description, "");
        this.defaultValue = defaultValue;
        this.legacyDefaults = legacyDefaults == null ? List.of() : List.copyOf(legacyDefaults);
        this.restartRequired = restartRequired;
        this.group = Objects.requireNonNullElse(group, "General");
        this.visibleWhen = visibleWhen;
    }

    /** Configuration path relative to the plugin section. */
    public final String path() {
        return path;
    }

    public final String displayName() {
        return displayName;
    }

    public final String description() {
        return description;
    }

    public final T defaultValue() {
        return defaultValue;
    }

    /**
     * Read-only defaults from older schema versions; empty when none are declared. Storage cannot
     * distinguish old defaults from intentional user choices. Only exact matches to these declared values
     * may migrate to the current default; see JsonConfigStore.
     */
    public final List<T> legacyDefaults() {
        return legacyDefaults;
    }

    /** Whether changes require pipeline reconstruction instead of a hot update. */
    public final boolean restartRequired() {
        return restartRequired;
    }

    /** UI group title. */
    public final String group() {
        return group;
    }

    /** Whether this option is currently visible, for dependent controls. */
    public final boolean isVisible() {
        return visibleWhen == null || Boolean.TRUE.equals(visibleWhen.get());
    }

    /** Read this option from a configuration view. */
    public abstract T read(ConfigView view);

    /** Write the canonical default. */
    public final void writeDefault(ConfigEditor editor) {
        editor.set(path, defaultValue);
    }

    /** Validate and normalize a value; null means invalid. */
    public abstract T coerce(Object raw);

    /** Diagnostic type name. */
    public abstract String typeName();

    /** Boolean switch. */
    public static final class Bool extends ConfigOption<Boolean> {
        private Bool(String path, String name, String desc, boolean def, List<Boolean> legacyDefaults,
                     boolean restart, String group, Supplier<Boolean> visible) {
            super(path, name, desc, def, legacyDefaults, restart, group, visible);
        }

        @Override
        public Boolean read(ConfigView view) {
            return view.getBoolean(path(), defaultValue());
        }

        @Override
        public Boolean coerce(Object raw) {
            if (raw instanceof Boolean b) {
                return b;
            }
            if (raw instanceof String s) {
                return Boolean.parseBoolean(s.trim());
            }
            return null;
        }

        @Override
        public String typeName() {
            return "boolean";
        }
    }

    /** Integer or floating-point number with range constraints. */
    public static final class Numeric extends ConfigOption<Double> {
        private final double min;
        private final double max;
        private final double step;
        private final boolean integer;

        private Numeric(String path, String name, String desc, double def, List<Double> legacyDefaults,
                        double min, double max, double step,
                        boolean integer, boolean restart, String group, Supplier<Boolean> visible) {
            super(path, name, desc, def, legacyDefaults, restart, group, visible);
            this.min = min;
            this.max = max;
            this.step = step;
            this.integer = integer;
        }

        public double min() {
            return min;
        }

        public double max() {
            return max;
        }

        public double step() {
            return step;
        }

        public boolean isInteger() {
            return integer;
        }

        @Override
        public Double read(ConfigView view) {
            double v = integer
                    ? view.getInt(path(), defaultValue().intValue())
                    : view.getFloat(path(), defaultValue().floatValue());
            return clamp(v);
        }

        private double clamp(double v) {
            return v < min ? min : (v > max ? max : v);
        }

        @Override
        public Double coerce(Object raw) {
            if (raw instanceof Number n) {
                return clamp(integer ? Math.rint(n.doubleValue()) : n.doubleValue());
            }
            if (raw instanceof String s) {
                try {
                    double v = Double.parseDouble(s.trim());
                    return clamp(integer ? Math.rint(v) : v);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public String typeName() {
            return integer ? "integer" : "number";
        }
    }

    /** Free text. */
    public static final class Text extends ConfigOption<String> {
        private final boolean multiline;

        private Text(String path, String name, String desc, String def, List<String> legacyDefaults,
                     boolean multiline, boolean restart,
                     String group, Supplier<Boolean> visible) {
            super(path, name, desc, Objects.requireNonNullElse(def, ""), legacyDefaults, restart, group, visible);
            this.multiline = multiline;
        }

        public boolean multiline() {
            return multiline;
        }

        @Override
        public String read(ConfigView view) {
            return view.getString(path(), defaultValue());
        }

        @Override
        public String coerce(Object raw) {
            return raw == null ? null : String.valueOf(raw);
        }

        @Override
        public String typeName() {
            return "string";
        }
    }

    /** Enum selection. */
    public static final class EnumOption<E extends Enum<E>> extends ConfigOption<String> {
        private final Class<E> type;
        private final List<String> allowedKeys;

        private EnumOption(String path, String name, String desc, E def, List<String> legacyDefaults,
                           Class<E> type, boolean restart,
                           String group, Supplier<Boolean> visible) {
            super(path, name, desc, keyOf(def), legacyDefaults, restart, group, visible);
            this.type = type;
            List<String> keys = new ArrayList<>();
            for (E e : type.getEnumConstants()) {
                keys.add(keyOf(e));
            }
            this.allowedKeys = List.copyOf(keys);
        }

        private static String keyOf(Enum<?> e) {
            return e == null ? "" : e.name().toLowerCase(java.util.Locale.ROOT);
        }

        public Class<E> enumType() {
            return type;
        }

        /** Allowed values as lowercase keys. */
        public List<String> allowedKeys() {
            return allowedKeys;
        }

        /** Parse a configuration string into an enum; return null on failure. */
        public E resolve(ConfigView view) {
            String raw = read(view);
            for (E e : type.getEnumConstants()) {
                if (keyOf(e).equalsIgnoreCase(raw)) {
                    return e;
                }
            }
            return null;
        }

        @Override
        public String read(ConfigView view) {
            String raw = view.getString(path(), defaultValue());
            for (String k : allowedKeys) {
                if (k.equalsIgnoreCase(raw)) {
                    return k;
                }
            }
            return defaultValue();
        }

        @Override
        public String coerce(Object raw) {
            if (raw == null) {
                return null;
            }
            String s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
            return allowedKeys.contains(s) ? s : null;
        }

        @Override
        public String typeName() {
            return "enum(" + String.join("|", allowedKeys) + ")";
        }
    }

    /** String list. */
    public static final class ListOption extends ConfigOption<List<String>> {
        private ListOption(String path, String name, String desc, List<String> def, List<List<String>> legacyDefaults,
                           boolean restart, String group, Supplier<Boolean> visible) {
            super(path, name, desc, def == null ? List.of() : List.copyOf(def), legacyDefaults, restart, group, visible);
        }

        @Override
        public List<String> read(ConfigView view) {
            return view.getStringList(path(), defaultValue());
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<String> coerce(Object raw) {
            if (raw instanceof List<?> l) {
                List<String> out = new ArrayList<>(l.size());
                for (Object o : l) {
                    out.add(String.valueOf(o));
                }
                return List.copyOf(out);
            }
            if (raw instanceof String s) {
                return List.of(s.split("\\s*,\\s*"));
            }
            return null;
        }

        @Override
        public String typeName() {
            return "list<string>";
        }
    }

    // Factories for declarative configuration.

    /** Boolean switch. */
    public static BoolBuilder bool(String path, boolean defaultValue) {
        return new BoolBuilder(path, defaultValue);
    }

    /** Floating-point value. */
    public static NumericBuilder number(String path, double defaultValue) {
        return new NumericBuilder(path, defaultValue);
    }

    /** Integer value. */
    public static NumericBuilder integer(String path, int defaultValue) {
        return new NumericBuilder(path, defaultValue).integer();
    }

    /** Text. */
    public static TextBuilder text(String path, String defaultValue) {
        return new TextBuilder(path, defaultValue);
    }

    /** Enum selection. */
    public static <E extends Enum<E>> EnumBuilder<E> enumOption(String path, Class<E> type, E defaultValue) {
        return new EnumBuilder<>(path, type, defaultValue);
    }

    /** String list. */
    public static ListBuilder list(String path, List<String> defaultValue) {
        return new ListBuilder(path, defaultValue);
    }

    /** Base option builder. */
    public abstract static class Builder<B extends Builder<B, T>, T> {
        protected final String path;
        protected String name;
        protected String description = "";
        protected boolean restartRequired;
        protected String group = "General";
        protected Supplier<Boolean> visibleWhen;
        /** Historical defaults in declaration order. */
        protected final List<T> legacyDefaults = new ArrayList<>();

        protected Builder(String path) {
            this.path = path;
        }

        @SuppressWarnings("unchecked")
        private B self() {
            return (B) this;
        }

        /** UI display name. */
        public B name(String name) {
            this.name = name;
            return self();
        }

        /** Tooltip text. */
        public B describe(String description) {
            this.description = description;
            return self();
        }

        /** Require pipeline reconstruction after a change. */
        public B restartRequired() {
            this.restartRequired = true;
            return self();
        }

        /** UI group. */
        public B group(String group) {
            this.group = group;
            return self();
        }

        /** Conditional visibility. */
        public B visibleWhen(Supplier<Boolean> condition) {
            this.visibleWhen = condition;
            return self();
        }

        /**
         * Declare a previous default. Only an exact match on disk may be replaced by the current default
         * during loading. Preserve all other user values. Call repeatedly for multiple historical defaults.
         */
        public B legacyDefault(T value) {
            this.legacyDefaults.add(Objects.requireNonNull(value, "legacyDefault"));
            return self();
        }
    }

    /** Boolean option builder. */
    public static final class BoolBuilder extends Builder<BoolBuilder, Boolean> {
        private final boolean def;

        public BoolBuilder(String path, boolean def) {
            super(path);
            this.def = def;
        }

        public Bool build() {
            return new Bool(path, name, description, def, legacyDefaults, restartRequired, group, visibleWhen);
        }
    }

    /** Numeric option builder. */
    public static final class NumericBuilder extends Builder<NumericBuilder, Double> {
        private final double def;
        private double min = Double.NEGATIVE_INFINITY;
        private double max = Double.POSITIVE_INFINITY;
        private double step = 0.01;
        private boolean integer;

        public NumericBuilder(String path, double def) {
            super(path);
            this.def = def;
        }

        public NumericBuilder range(double min, double max) {
            this.min = min;
            this.max = max;
            return this;
        }

        public NumericBuilder step(double step) {
            this.step = step;
            return this;
        }

        /** Use an integer control in the UI. */
        public NumericBuilder integer() {
            this.integer = true;
            this.step = Math.max(1, step);
            return this;
        }

        /**
         * Numeric overload for historical defaults. An int boxes to Integer, not Double, so the inherited
         * legacyDefault(Double) cannot accept integer arguments.
         */
        public NumericBuilder legacyDefault(double value) {
            this.legacyDefaults.add(value);
            return this;
        }

        public Numeric build() {
            return new Numeric(path, name, description, def, legacyDefaults, min, max, step, integer,
                    restartRequired, group, visibleWhen);
        }
    }

    /** Text option builder. */
    public static final class TextBuilder extends Builder<TextBuilder, String> {
        private final String def;
        private boolean multiline;

        public TextBuilder(String path, String def) {
            super(path);
            this.def = def;
        }

        public TextBuilder multiline() {
            this.multiline = true;
            return this;
        }

        public Text build() {
            return new Text(path, name, description, def, legacyDefaults, multiline, restartRequired, group, visibleWhen);
        }
    }

    /** Enum option builder. */
    public static final class EnumBuilder<E extends Enum<E>> extends Builder<EnumBuilder<E>, String> {
        private final Class<E> type;
        private final E def;

        public EnumBuilder(String path, Class<E> type, E def) {
            super(path);
            this.type = type;
            this.def = def;
        }

        /** Accept an enum constant as a historical default and store its lowercase file key. */
        public EnumBuilder<E> legacyDefault(E value) {
            this.legacyDefaults.add(ConfigOption.EnumOption.keyOf(value));
            return this;
        }

        public EnumOption<E> build() {
            return new EnumOption<>(path, name, description, def, legacyDefaults, type, restartRequired, group, visibleWhen);
        }
    }

    /** List option builder. */
    public static final class ListBuilder extends Builder<ListBuilder, List<String>> {
        private final List<String> def;

        public ListBuilder(String path, List<String> def) {
            super(path);
            this.def = def == null ? List.of() : List.copyOf(def);
        }

        public ListOption build() {
            return new ListOption(path, name, description, def, legacyDefaults, restartRequired, group, visibleWhen);
        }
    }

    /** Change callbacks dispatched by ConfigSchema.notifyListeners(String). */
    interface Listener {
        void onChanged(String path);
    }

    /** Adapt a change callback to Consumer. */
    static Consumer<String> asConsumer(Listener listener) {
        return listener::onChanged;
    }
}
