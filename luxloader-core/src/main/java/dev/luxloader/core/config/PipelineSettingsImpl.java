package dev.luxloader.core.config;

import dev.luxloader.api.config.ConfigSchema;
import dev.luxloader.api.config.ConfigView;
import dev.luxloader.api.pipeline.PipelineSettings;

import java.util.List;
import java.util.Optional;

/**
 * Adapt ConfigView to PipelineSettings. The revision counter lets frame loops reread settings only
 * after changes without callbacks or repeated string comparisons.
 */
public final class PipelineSettingsImpl implements PipelineSettings {

    private final ConfigView view;
    private final ConfigSchema schema;
    private final String source;
    private final long revision;
    private final Runnable editorOpener;

    public PipelineSettingsImpl(ConfigView view, ConfigSchema schema, String source, long revision) {
        this(view, schema, source, revision, null);
    }

    public PipelineSettingsImpl(ConfigView view, ConfigSchema schema, String source, long revision,
                               Runnable editorOpener) {
        this.view = view;
        this.schema = schema;
        this.source = source == null ? "file" : source;
        this.revision = revision;
        this.editorOpener = editorOpener;
    }

    @Override
    public boolean getBoolean(String path, boolean fallback) {
        return withSchema(path, o -> o instanceof dev.luxloader.api.config.ConfigOption.Bool)
                .map(o -> (Boolean) ((dev.luxloader.api.config.ConfigOption<?>) o).read(view))
                .orElseGet(() -> view.getBoolean(path, fallback));
    }

    @Override
    public int getInt(String path, int fallback) {
        Optional<dev.luxloader.api.config.ConfigOption<?>> option =
                withSchema(path, o -> o instanceof dev.luxloader.api.config.ConfigOption.Numeric n && n.isInteger());
        if (option.isPresent()) {
            Object v = option.get().read(view);
            return v instanceof Number n ? n.intValue() : fallback;
        }
        return view.getInt(path, fallback);
    }

    @Override
    public long getLong(String path, long fallback) {
        return view.getLong(path, fallback);
    }

    @Override
    public float getFloat(String path, float fallback) {
        Optional<dev.luxloader.api.config.ConfigOption<?>> option =
                withSchema(path, o -> o instanceof dev.luxloader.api.config.ConfigOption.Numeric n && !n.isInteger());
        if (option.isPresent()) {
            Object v = option.get().read(view);
            return v instanceof Number n ? n.floatValue() : fallback;
        }
        return view.getFloat(path, fallback);
    }

    @Override
    public String getString(String path, String fallback) {
        Optional<dev.luxloader.api.config.ConfigOption<?>> option =
                withSchema(path, o -> o instanceof dev.luxloader.api.config.ConfigOption.EnumOption<?>
                        || o instanceof dev.luxloader.api.config.ConfigOption.Text);
        if (option.isPresent()) {
            Object v = option.get().read(view);
            return v == null ? fallback : String.valueOf(v);
        }
        return view.getString(path, fallback);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> E getEnum(String path, Class<E> type, E fallback) {
        Optional<dev.luxloader.api.config.ConfigOption<?>> option =
                withSchema(path, o -> o instanceof dev.luxloader.api.config.ConfigOption.EnumOption<?>);
        if (option.isPresent()
                && option.get() instanceof dev.luxloader.api.config.ConfigOption.EnumOption<?> eo
                && eo.enumType() == type) {
            Object resolved = eo.resolve(view);
            if (resolved != null) {
                return (E) resolved;
            }
        }
        return view.getEnum(path, type, fallback);
    }

    @Override
    public <E extends Enum<E>> E getEnumOrDefault(String path, Class<E> type, E fallback) {
        return getEnum(path, type, fallback);
    }

    @Override
    public List<String> getStringList(String path, List<String> fallback) {
        return view.getStringList(path, fallback);
    }

    @Override
    public boolean has(String path) {
        return view.has(path);
    }

    @Override
    public Optional<Object> raw(String path) {
        return view.raw(path);
    }

    @Override
    public List<String> childKeys() {
        return view.childKeys();
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public boolean openEditor() {
        if (editorOpener == null) {
            return false;
        }
        editorOpener.run();
        return true;
    }

    /** Underlying view for loader internals. */
    public ConfigView view() {
        return view;
    }

    /** Schema for loader internals; may be null. */
    public ConfigSchema schema() {
        return schema;
    }

    private Optional<dev.luxloader.api.config.ConfigOption<?>> withSchema(
            String path, java.util.function.Predicate<dev.luxloader.api.config.ConfigOption<?>> filter) {
        if (schema == null) {
            return Optional.empty();
        }
        return schema.find(path).filter(filter);
    }
}
