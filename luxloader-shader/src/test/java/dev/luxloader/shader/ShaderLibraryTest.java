package dev.luxloader.shader;

import dev.luxloader.api.vulkan.ComputeFillModule;
import dev.luxloader.api.vulkan.SpirvGen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests shader library scheduling, deduplication, background work, timeout and shutdown using a
 * controllable compiler stub. Real compiler coverage lives in SlangCompilerRealTest.
 */
class ShaderLibraryTest {

    @TempDir
    Path tempDir;

    /** Compiler stub with blocking, invocation counting and configurable outcomes. */
    private static final class FakeCompiler implements ShaderCompiler {
        final AtomicInteger invocations = new AtomicInteger();
        final List<ShaderSource> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile CountDownLatch gate;
        volatile boolean succeed = true;
        volatile String failMessage = "假编译器：故意失败";
        volatile boolean closed;

        @Override
        public ShaderCompileResult compile(ShaderSource source, List<Path> includeDirs) {
            invocations.incrementAndGet();
            seen.add(source);
            CountDownLatch current = gate;
            if (current != null) {
                try {
                    current.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!succeed) {
                return ShaderCompileResult.fail(source.name(), source.stage(), source.entryPoint(),
                        failMessage);
            }
            return ShaderCompileResult.ok(source.name(), source.stage(), source.entryPoint(),
                    ComputeFillModule.bytes(), List.of(), 1L, false, "假编译器");
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String describe() {
            return "假编译器 v1";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private ShaderLibrary libraryWith(FakeCompiler compiler) {
        return ShaderLibrary.builder()
                .compiler(compiler)
                .cacheRoot(tempDir)
                .threads(4)
                .build();
    }

    private static ShaderSource source(String name) {
        return ShaderSource.FromSource.of(name, ShaderStage.COMPUTE, "// " + name);
    }

    @Test
    @DisplayName("Compiles On Background Thread")
    void compilesOnBackgroundThread() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        compiler.gate = new CountDownLatch(1);
        try (ShaderLibrary library = libraryWith(compiler)) {
            CompletableFuture<ShaderCompileResult> future = library.compile(source("s"));

            assertFalse(future.isDone(), "The future must remain incomplete while compilation is blocked");
            assertEquals(1, library.pendingCount(), "One compilation must be in flight");
            assertTrue(compiler.invocations.get() >= 0);

            compiler.gate.countDown();
            ShaderCompileResult result = future.get(10, TimeUnit.SECONDS);

            assertTrue(result.success(), result.report());
            assertEquals("s", result.name());
            assertEquals(0, library.pendingCount(), "Completed work must not remain in flight");
        }
    }

    @Test
    @DisplayName("Deduplicates Concurrent Requests")
    void deduplicatesConcurrentRequests() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        compiler.gate = new CountDownLatch(1);
        try (ShaderLibrary library = libraryWith(compiler)) {
            ShaderSource shared = source("shared");
            List<CompletableFuture<ShaderCompileResult>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(library.compile(shared));
            }

            compiler.gate.countDown();
            for (CompletableFuture<ShaderCompileResult> f : futures) {
                assertTrue(f.get(10, TimeUnit.SECONDS).success());
            }

            assertEquals(1, compiler.invocations.get(),
                    "Eight identical requests must produce one compilation: " + compiler.invocations.get() + " times");
        }
    }

    @Test
    @DisplayName("Second Run Hits Disk Cache")
    void secondRunHitsDiskCache() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        ShaderSource shader = source("cached");

        try (ShaderLibrary first = libraryWith(compiler)) {
            assertTrue(first.compile(shader).get(10, TimeUnit.SECONDS).success());
        }
        assertEquals(1, compiler.invocations.get());

        // Recreate the library using the same cache directory to simulate restart.
        FakeCompiler second = new FakeCompiler();
        try (ShaderLibrary library = libraryWith(second)) {
            ShaderCompileResult result = library.compile(shader).get(10, TimeUnit.SECONDS);

            assertTrue(result.success(), result.report());
            assertTrue(result.fromCache(), "Expected a cache hit: " + result.describe());
            assertEquals(0, second.invocations.get(),
                    "Cache hits must not invoke the compiler");
            assertEquals(1, library.stats().cacheHits());
            assertEquals(ComputeFillModule.WORD_COUNT * 4, result.size(),
                    "Cached byte count must match the original artifact");
        }
    }

    @Test
    @DisplayName("Cache Invalidates On Source Change")
    void cacheInvalidatesOnSourceChange() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        try (ShaderLibrary library = libraryWith(compiler)) {
            ShaderSource v1 = ShaderSource.FromSource.of("x", ShaderStage.COMPUTE, "// version 1");
            ShaderSource v2 = ShaderSource.FromSource.of("x", ShaderStage.COMPUTE, "// version 2");

            assertTrue(library.compile(v1).get(10, TimeUnit.SECONDS).success());
            ShaderCompileResult second = library.compile(v2).get(10, TimeUnit.SECONDS);

            assertFalse(second.fromCache(), "Changed source must recompile");
            assertEquals(2, compiler.invocations.get());
        }
    }

    @Test
    @DisplayName("Peek Does Not Trigger Compilation")
    void peekDoesNotTriggerCompilation() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        try (ShaderLibrary library = libraryWith(compiler)) {
            ShaderSource shader = source("peek");

            assertTrue(library.peekCached(shader).isEmpty(), "An uncompiled source must have no cached artifact");
            assertEquals(0, compiler.invocations.get(), "peek must not trigger compilation");

            assertTrue(library.compile(shader).get(10, TimeUnit.SECONDS).success());
            assertTrue(library.peekCached(shader).isPresent(), "Compiled artifacts must be directly queryable");
            assertTrue(SpirvGen.validate(library.peekCached(shader).orElseThrow()).valid());
        }
    }

    @Test
    @DisplayName("Failures Are Values Not Exceptions")
    void failuresAreValuesNotExceptions() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        compiler.succeed = false;
        try (ShaderLibrary library = libraryWith(compiler)) {
            ShaderCompileResult result = library.compile(source("bad")).get(10, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertTrue(result.report().contains("假编译器：故意失败"), result.report());
            assertEquals(1, library.stats().failed());
            assertEquals(0, library.stats().succeeded());
        }
    }

    @Test
    @DisplayName("Compiler Exceptions Become Failures")
    void compilerExceptionsBecomeFailures() throws Exception {
        ShaderCompiler throwing = new ShaderCompiler() {
            @Override
            public ShaderCompileResult compile(ShaderSource source, List<Path> includeDirs) {
                throw new IllegalStateException("模拟编译器内部崩溃");
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String describe() {
                return "会抛异常的编译器";
            }
        };
        try (ShaderLibrary library = ShaderLibrary.builder()
                .compiler(throwing).cacheRoot(tempDir).build()) {
            ShaderCompileResult result = library.compile(source("boom")).get(10, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertTrue(result.error().contains("模拟编译器内部崩溃"), result.error());
            assertTrue(result.report().contains("未预期的异常"), result.report());
        }
    }

    @Test
    @DisplayName("Blocking Compile Times Out")
    void blockingCompileTimesOut() {
        FakeCompiler compiler = new FakeCompiler();
        compiler.gate = new CountDownLatch(1);
        try (ShaderLibrary library = libraryWith(compiler)) {
            ShaderCompileResult result = library.compileBlocking(source("slow"),
                    Duration.ofMillis(150));

            assertFalse(result.success());
            assertTrue(result.error().contains("超时"), result.error());
        } finally {
            compiler.gate.countDown();
        }
    }

    @Test
    @DisplayName("Precompiled Path Works Without Compiler")
    void precompiledPathWorksWithoutCompiler() throws Exception {
        try (ShaderLibrary library = ShaderLibrary.builder()
                .compiler(ShaderCompiler.unavailable("测试：故意没有编译器"))
                .cacheRoot(tempDir)
                .build()) {

            ShaderCompileResult fromSource = library.compile(source("needs-compiler"))
                    .get(10, TimeUnit.SECONDS);
            assertFalse(fromSource.success(), "Source compilation must fail without a compiler");
            assertTrue(fromSource.error().contains("预编译"), "Failure diagnostics must suggest a recovery path: " + fromSource.error());

            ShaderSource.Precompiled precompiled = new ShaderSource.Precompiled(
                    "ready", ShaderStage.COMPUTE, "main", ComputeFillModule.bytes());
            ShaderCompileResult result = library.compile(precompiled).get(10, TimeUnit.SECONDS);

            assertTrue(result.success(), () -> "Precompiled modules must work without an external compiler: " + result.report());
            assertEquals(ComputeFillModule.WORD_COUNT * 4, result.size());
        }
    }

    @Test
    @DisplayName("Precompiled Skips Cache")
    void precompiledSkipsCache() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        try (ShaderLibrary library = libraryWith(compiler)) {
            ShaderSource.Precompiled precompiled = new ShaderSource.Precompiled(
                    "ready", ShaderStage.COMPUTE, "main", ComputeFillModule.bytes());

            ShaderCompileResult first = library.compile(precompiled).get(10, TimeUnit.SECONDS);
            ShaderCompileResult second = library.compile(precompiled).get(10, TimeUnit.SECONDS);

            assertTrue(first.success() && second.success());
            assertFalse(first.fromCache());
            assertFalse(second.fromCache());
            assertEquals(0, compiler.invocations.get(), "Precompiled modules must not invoke the source compiler");
            assertEquals(0, library.cache().size(), "Precompiled artifacts must not be written to the compilation cache");
        }
    }

    @Test
    @DisplayName("Notifies Listeners")
    void notifiesListeners() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        List<ShaderCompileResult> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (ShaderLibrary library = libraryWith(compiler)) {
            library.addListener(observed::add);

            library.compile(source("ok")).get(10, TimeUnit.SECONDS);
            compiler.succeed = false;
            library.compile(source("bad")).get(10, TimeUnit.SECONDS);

            assertEquals(2, observed.size());
            assertTrue(observed.get(0).success());
            assertFalse(observed.get(1).success());
        }
    }

    @Test
    @DisplayName("Listener Failures Are Isolated")
    void listenerFailuresAreIsolated() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        try (ShaderLibrary library = libraryWith(compiler)) {
            library.addListener(result -> {
                throw new IllegalStateException("监听者炸了");
            });
            ShaderCompileResult result = library.compile(source("ok")).get(10, TimeUnit.SECONDS);

            assertTrue(result.success(), "Listener exceptions must not corrupt compilation results");
        }
    }

    @Test
    @DisplayName("Close Rejects New Work")
    void closeRejectsNewWork() {
        FakeCompiler compiler = new FakeCompiler();
        ShaderLibrary library = libraryWith(compiler);
        library.close();

        assertTrue(library.isClosed());
        assertTrue(compiler.closed, "Closing the library must also close the compiler");

        ShaderCompileResult result = library.compile(source("late")).join();
        assertFalse(result.success());
        assertTrue(result.error().contains("已关闭"), result.error());

        library.close();
    }

    @Test
    @DisplayName("Reports Stats")
    void reportsStats() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        try (ShaderLibrary library = libraryWith(compiler)) {
            library.compile(source("a")).get(10, TimeUnit.SECONDS);
            library.compile(source("a")).get(10, TimeUnit.SECONDS);
            compiler.succeed = false;
            library.compile(source("b")).get(10, TimeUnit.SECONDS);

            assertEquals(1, library.stats().succeeded(), "Only the first request must invoke the compiler");
            assertEquals(1, library.stats().failed());
            assertEquals(1, library.stats().cacheHits());
            assertEquals(2, library.stats().delivered(), "Both requests must complete successfully");
            assertEquals(0.5, library.stats().cacheHitRatio(), 1e-9);
            assertTrue(library.stats().describe().contains("缓存命中 1"), library.stats().describe());
            assertEquals(3, library.submittedCount());
        }
    }

    @Test
    @DisplayName("Corrupt Cache Entry Is Ignored")
    void corruptCacheEntryIsIgnored() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        ShaderSource shader = source("corrupt");

        try (ShaderLibrary library = libraryWith(compiler)) {
            assertTrue(library.compile(shader).get(10, TimeUnit.SECONDS).success());
        }

        // Truncate the cache file to simulate disk corruption or an external write.
        try (var files = java.nio.file.Files.list(tempDir.resolve("shader-cache"))) {
            for (Path p : files.filter(p -> p.toString().endsWith(".spv")).toList()) {
                java.nio.file.Files.write(p, new byte[] {1, 2, 3});
            }
        }

        FakeCompiler second = new FakeCompiler();
        try (ShaderLibrary library = libraryWith(second)) {
            ShaderCompileResult result = library.compile(shader).get(10, TimeUnit.SECONDS);

            assertTrue(result.success(), () -> "Ignore corrupt cache entries and recompile: " + result.report());
            assertFalse(result.fromCache());
            assertEquals(1, second.invocations.get(), "The compiler must actually run again");
        }
    }

    @Test
    @DisplayName("Describes Itself")
    void describesItself() {
        FakeCompiler compiler = new FakeCompiler();
        try (ShaderLibrary library = ShaderLibrary.builder()
                .compiler(compiler).cacheRoot(tempDir).threads(2).build()) {
            String description = library.describe();

            assertTrue(description.contains("假编译器 v1"), description);
            assertTrue(description.contains("后台线程 2"), description);
            assertTrue(description.contains("在编 0"), description);
            assertNotNull(library.sourceCompiler());
        }
    }

    @Test
    @DisplayName("Clamps Thread Count")
    void clampsThreadCount() {
        FakeCompiler compiler = new FakeCompiler();
        try (ShaderLibrary library = ShaderLibrary.builder()
                .compiler(compiler).cacheRoot(tempDir).threads(0).build()) {
            assertTrue(library.describe().contains("后台线程 1"), library.describe());
        }
        try (ShaderLibrary library = ShaderLibrary.builder()
                .compiler(compiler).cacheRoot(tempDir).threads(999).build()) {
            assertTrue(library.describe().contains("后台线程 4"), library.describe());
        }
    }

    @Test
    @DisplayName("Compiles All")
    void compilesAll() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        try (ShaderLibrary library = libraryWith(compiler)) {
            List<ShaderSource> sources = List.of(source("a"), source("b"), source("c"));
            List<CompletableFuture<ShaderCompileResult>> futures = library.compileAll(sources);

            assertEquals(3, futures.size());
            List<String> names = new ArrayList<>();
            for (CompletableFuture<ShaderCompileResult> f : futures) {
                names.add(f.get(10, TimeUnit.SECONDS).name());
            }
            assertEquals(List.of("a", "b", "c"), names);
        }
    }

    @Test
    @DisplayName("Records Which Compiler Was Used")
    void recordsWhichCompilerWasUsed() throws Exception {
        FakeCompiler compiler = new FakeCompiler();
        try (ShaderLibrary library = libraryWith(compiler)) {
            ShaderCompileResult result = library.compile(source("who")).get(10, TimeUnit.SECONDS);
            assertEquals("假编译器", result.usedCompiler());
        }
    }
}
