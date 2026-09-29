package dev.luxloader.mc.hooks;

import dev.luxloader.api.resource.ResourceKey;
import dev.luxloader.api.scene.ResourceAccess;
import dev.luxloader.core.runtime.ResourcePreparationHub;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.resources.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePackWinnerTest {
    @Test void realMinecraftManagerResolvesTheLastPackAndMissingStaysDistinctFromFailure() throws Exception {
        Thread host = Thread.currentThread();
        PackResources low = pack("low", 7, host), high = pack("high", 9, host);
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(low, high))) {
            ResourceAccess access = new ResourceAccess() {
                public long revision() { return 1; }
                public Optional<byte[]> read(String namespace, String path) { throw new AssertionError("Stream only"); }
                public Optional<InputStream> open(ResourceKey key) throws IOException {
                    assertSame(host, Thread.currentThread());
                    var resource = manager.getResource(Identifier.fromNamespaceAndPath(key.namespace(), key.path()));
                    return resource.isPresent() ? Optional.of(resource.get().open()) : Optional.empty();
                }
            };
            var key = new ResourceKey("test", "textures/winner.png");
            var missing = new ResourceKey("test", "textures/missing.png");
            try (var hub = new ResourcePreparationHub(() -> access); var scope = hub.serviceForOwner(1).openScope()) {
                var task = scope.submit(List.of(key, missing), input -> {
                    assertNotSame(host, Thread.currentThread()); assertTrue(input.bytes(missing).isEmpty());
                    return (int)input.bytes(key).orElseThrow().get();
                });
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (task.result().isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(9, task.result().orElseThrow());
                assertEquals(List.of(key, missing), task.dependencies());
            }
        }
    }
    private static PackResources pack(String name, int value, Thread host) {
        return (PackResources)Proxy.newProxyInstance(PackResources.class.getClassLoader(), new Class<?>[]{PackResources.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getNamespaces" -> Set.of("test");
                    case "packId", "toString" -> name;
                    case "getMetadataSection" -> null;
                    case "getResource" -> {
                        assertSame(host, Thread.currentThread());
                        var id = (Identifier)arguments[1];
                        yield id.getPath().equals("textures/winner.png") ? (IoSupplier<InputStream>)() -> {
                            assertSame(host, Thread.currentThread()); return new ByteArrayInputStream(new byte[]{(byte)value});
                        } : null;
                    }
                    case "close" -> null;
                    default -> throw new AssertionError(method.getName());
                });
    }
}
