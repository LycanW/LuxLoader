package dev.luxloader.mc;

import dev.luxloader.api.scene.ResourceAccess;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Optional;
import dev.luxloader.api.resource.ResourceKey;
import dev.luxloader.api.resource.ResourceState;

/** Minecraft-specific resource lookup stays on the adapter side of the API. */
public final class MinecraftResourceAccess implements ResourceAccess {
    private static final ResourceReloadState reload = new ResourceReloadState();
    private static final java.util.Map<String, dev.luxloader.api.scene.TextureAnimation> animations =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Method instance, manager, identifier, resource, open;

    public MinecraftResourceAccess(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> mc = Class.forName("net.minecraft.client.Minecraft", false, loader);
        Class<?> id = Class.forName("net.minecraft.resources.Identifier", false, loader);
        Class<?> rm = Class.forName("net.minecraft.server.packs.resources.ResourceManager", false, loader);
        Class<?> entry = Class.forName("net.minecraft.server.packs.resources.Resource", false, loader);
        instance = mc.getMethod("getInstance");
        manager = mc.getMethod("getResourceManager");
        identifier = id.getMethod("fromNamespaceAndPath", String.class, String.class);
        resource = rm.getMethod("getResource", id);
        open = entry.getMethod("open");
    }

    public static long reloaded() { long token = reload.begin(); animations.clear(); return token; }
    public static ResourceState currentState() { return reload.current(); }
    public static void contentsReady() { contentsFinished(reload.current().generation(), null); }
    public static void contentsFinished(long token, Throwable failure) { reload.finish(token, failure); }
    public static void closed() { reload.close(); animations.clear(); }
    public static <T> T trackReload(java.util.function.Supplier<T> start,
                                   java.util.function.Function<T, java.util.concurrent.CompletionStage<?>> completion) {
        animations.clear();
        MinecraftCompiledScene.instance().resourceReloadStarted();
        return reload.track(start, completion);
    }
    public static void publishAnimation(String id, dev.luxloader.api.scene.TextureAnimation frame) {
        animations.put(id, frame);
    }
    @Override public dev.luxloader.api.scene.TextureAnimation animation(dev.luxloader.api.scene.TextureRef texture) {
        return animations.getOrDefault(texture.id(), dev.luxloader.api.scene.TextureAnimation.STATIC);
    }
    @Override public long revision() { return reload.current().generation(); }
    @Override public ResourceState state() { return reload.current(); }

    @Override public Optional<byte[]> read(String namespace, String path) throws IOException {
        Optional<InputStream> stream = open(new ResourceKey(namespace, path));
        if (stream.isEmpty()) return Optional.empty();
        try (InputStream input = stream.get()) { return Optional.of(input.readAllBytes()); }
    }

    @Override public Optional<InputStream> open(ResourceKey key) throws IOException {
        ResourceState acquired = state();
        if (!acquired.ready()) throw new IOException("Resource stack is " + acquired.phase());
        try {
            Object id = identifier.invoke(null, key.namespace(), key.path());
            Object rm = manager.invoke(instance.invoke(null));
            Optional<?> entry = (Optional<?>) resource.invoke(rm, id);
            if (entry.isEmpty()) return Optional.empty();
            InputStream input = (InputStream) open.invoke(entry.get());
            if (!acquired.equals(state())) {
                input.close();
                throw new IOException("Resource generation changed during stream acquisition");
            }
            return Optional.of(input);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("Cannot open resource " + key, e);
        }
    }
}
