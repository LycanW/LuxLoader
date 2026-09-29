package dev.luxloader.mc;

import dev.luxloader.api.scene.ResourceAccess;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Minecraft-specific resource lookup stays on the adapter side of the API. */
public final class MinecraftResourceAccess implements ResourceAccess {
    private static final AtomicLong revision = new AtomicLong();
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

    public static void reloaded() { revision.incrementAndGet(); animations.clear(); }
    public static void contentsReady() { revision.incrementAndGet(); }
    public static void publishAnimation(String id, dev.luxloader.api.scene.TextureAnimation frame) {
        animations.put(id, frame);
    }
    @Override public dev.luxloader.api.scene.TextureAnimation animation(dev.luxloader.api.scene.TextureRef texture) {
        return animations.getOrDefault(texture.id(), dev.luxloader.api.scene.TextureAnimation.STATIC);
    }
    @Override public long revision() { return revision.get(); }

    @Override public Optional<byte[]> read(String namespace, String path) throws IOException {
        try {
            Object id = identifier.invoke(null, namespace, path);
            Object rm = manager.invoke(instance.invoke(null));
            Optional<?> entry = (Optional<?>) resource.invoke(rm, id);
            if (entry.isEmpty()) return Optional.empty();
            try (InputStream input = (InputStream) open.invoke(entry.get())) {
                return Optional.of(input.readAllBytes());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("Cannot read resource " + namespace + ":" + path, e);
        }
    }
}
