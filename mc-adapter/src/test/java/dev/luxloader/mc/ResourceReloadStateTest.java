package dev.luxloader.mc;

import dev.luxloader.api.resource.ResourceState.Phase;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class ResourceReloadStateTest {
    @Test void trackedFutureFailureCancellationAndCloseAllRejectLateCompletions() {
        var lifecycle = new ResourceReloadState();
        var failed = new java.util.concurrent.CompletableFuture<Void>();
        lifecycle.track(() -> failed, result -> result); failed.completeExceptionally(new IOException("Reload failed"));
        assertEquals(Phase.FAILED,lifecycle.current().phase());
        var cancelled = new java.util.concurrent.CompletableFuture<Void>();
        lifecycle.track(() -> cancelled,result -> result); cancelled.cancel(false);
        assertEquals(Phase.CANCELLED,lifecycle.current().phase());
        var late = new java.util.concurrent.CompletableFuture<Void>();
        lifecycle.track(() -> late,result -> result); lifecycle.close();
        long closed = lifecycle.current().generation(); late.complete(null);
        assertEquals(closed,lifecycle.current().generation());assertEquals(Phase.CLOSED,lifecycle.current().phase());
    }
    @Test void synchronousReloadConstructionFailureBecomesFailedAndCanRecover() {
        var lifecycle = new ResourceReloadState();
        assertThrows(IllegalStateException.class, () -> lifecycle.track(() -> {
            assertEquals(Phase.RELOADING, lifecycle.current().phase());
            throw new IllegalStateException("Pack close or reload construction failed");
        }, ignored -> java.util.concurrent.CompletableFuture.completedFuture(null)));
        assertEquals(Phase.FAILED, lifecycle.current().phase());
        var future = new java.util.concurrent.CompletableFuture<Void>();
        lifecycle.track(() -> future, result -> result);
        assertEquals(Phase.RELOADING, lifecycle.current().phase());
        future.complete(null); assertEquals(Phase.READY, lifecycle.current().phase());
    }
    @Test void successFailureAndCancellationAdvanceGeneration() {
        var lifecycle = new ResourceReloadState();
        long first = lifecycle.begin(); assertEquals(Phase.RELOADING, lifecycle.current().phase());
        assertTrue(lifecycle.finish(first, null)); assertEquals(Phase.READY, lifecycle.current().phase());
        long second = lifecycle.begin(); assertTrue(second > first);
        lifecycle.finish(second, new IOException()); assertEquals(Phase.FAILED, lifecycle.current().phase());
        long third = lifecycle.begin(); lifecycle.finish(third, new CompletionException(new CancellationException()));
        assertEquals(Phase.CANCELLED, lifecycle.current().phase());
    }
    @Test void lateCompletionCannotReviveReplacedOrClosedStack() {
        var lifecycle = new ResourceReloadState(); long old = lifecycle.begin(), current = lifecycle.begin();
        assertFalse(lifecycle.finish(old, null)); assertEquals(current, lifecycle.current().generation());
        lifecycle.close(); assertFalse(lifecycle.finish(current, null)); assertEquals(Phase.CLOSED, lifecycle.current().phase());
    }
    @Test void resourceReflectionAndReloadContractMatchInstalledMinecraft() throws Exception {
        var jar = MinecraftClientJar.resolve(); org.junit.jupiter.api.Assumptions.assumeTrue(jar != null);
        try (var loader = MinecraftClientJar.classLoaderFor(jar)) {
            assertDoesNotThrow(() -> new MinecraftResourceAccess(loader));
            Class<?> manager = loader.loadClass("net.minecraft.server.packs.resources.ReloadableResourceManager");
            assertNotNull(manager.getDeclaredMethod("close"));
            assertNotNull(manager.getDeclaredField("type"));
            assertNotNull(loader.loadClass("net.minecraft.server.packs.resources.ReloadInstance").getMethod("done"));
        }
    }
}
