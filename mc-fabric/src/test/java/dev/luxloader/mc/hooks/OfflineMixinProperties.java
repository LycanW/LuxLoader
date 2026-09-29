package dev.luxloader.mc.hooks;

import org.spongepowered.asm.service.*;
import java.util.HashMap;
import java.util.Map;

public final class OfflineMixinProperties implements IGlobalPropertyService {
    private record Key(String name) implements IPropertyKey { }
    private final Map<IPropertyKey, Object> properties = new HashMap<>();
    public IPropertyKey resolveKey(String name) { return new Key(name); }
    @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key) { return (T)properties.get(key); }
    public void setProperty(IPropertyKey key, Object value) { properties.put(key, value); }
    public <T> T getProperty(IPropertyKey key, T fallback) { T value = getProperty(key); return value == null ? fallback : value; }
    public String getPropertyString(IPropertyKey key, String fallback) { Object value = properties.get(key); return value == null ? fallback : value.toString(); }
}
