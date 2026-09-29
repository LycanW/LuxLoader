package dev.luxloader.api.config;

import java.util.List;

/** Writable configuration section used only by ConfigSchema defaults and migration callbacks. */
public interface ConfigEditor extends ConfigView {

    @Override
    void set(String path, Object value);

    @Override
    boolean remove(String path);
}
