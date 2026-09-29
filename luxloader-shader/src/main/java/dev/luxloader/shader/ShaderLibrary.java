package dev.luxloader.shader;

import static dev.luxloader.api.i18n.Messages.tr;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Shader library entry point: selects source compilation or precompiled validation, checks disk cache,
 * compiles in background workers and coalesces duplicate requests. compile returns immediately;
 * consume its future when ready rather than blocking the frame loop. Failures carry structured
 * diagnostics and localized guidance so the pipeline can fall back instead of crashing the client.
 */
public final class ShaderLibrary implements AutoCloseable {

    /** Limit background compiler processes to avoid CPU oversubscription. */
    private static final int MAX_COMPILE_THREADS = 4;

    private final ShaderCompiler sourceCompiler;
    private final ShaderCompiler fallbackCompiler;
    private final ShaderCache cache;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, CompletableFuture<ShaderCompileResult>> inFlight =
            new ConcurrentHashMap<>();
    private final List<Consumer<ShaderCompileResult>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Stats stats = new Stats();
    private final AtomicLong submitted = new AtomicLong();
    private volatile boolean closed;

    private ShaderLibrary(ShaderCompiler sourceCompiler, ShaderCompiler precompiledCompiler,
                          ShaderCache cache, int threads) {
        this.sourceCompiler = Objects.requireNonNull(sourceCompiler, "sourceCompiler");
        this.fallbackCompiler = Objects.requireNonNull(precompiledCompiler, "precompiledCompiler");
        this.cache = cache;
        this.executor = Executors.newFixedThreadPool(threads, new CompileThreadFactory());
    }

    /** Builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Default library with automatic compiler lookup and ~/.luxloader/shader-cache. Precompiled input
     * remains usable without a source compiler.
     */
    public static ShaderLibrary createDefault() {
        return builder().build();
    }

    /** Builder with configurable toolchain, cache and worker count. */
    public static final class Builder {
        private ShaderCompiler injectedCompiler;
        private Path slangcPath;
        private Path cacheDir;
        private Path workDir;
        private Path cacheRoot = SlangToolchain.defaultCacheRoot();
        private int threads = defaultThreadCount();
        private boolean debugInfo;
        private Duration timeout;

        /** Explicit executable overrides bundled and PATH compilers. */
        public Builder compilerPath(Path path) {
            this.slangcPath = path;
            return this;
        }

        /** Injects a custom compiler for alternate build systems or controlled tests, bypassing lookup and compilerPath. */
        public Builder compiler(ShaderCompiler value) {
            this.injectedCompiler = value;
            return this;
        }

        /** Cache root, default ~/.luxloader. */
        public Builder cacheRoot(Path root) {
            this.cacheRoot = root;
            return this;
        }

        /** Shader cache directory, default <cacheRoot>/shader-cache. */
        public Builder cacheDirectory(Path dir) {
            this.cacheDir = dir;
            return this;
        }

        /** Compiler work directory, default <cacheRoot>/shader-work. */
        public Builder workDirectory(Path dir) {
            this.workDir = dir;
            return this;
        }

        /** Background compilation worker count. */
        public Builder threads(int count) {
            this.threads = Math.max(1, Math.min(MAX_COMPILE_THREADS, count));
            return this;
        }

        /** Includes SPIR-V debug information for GPU fault diagnosis. */
        public Builder debugInfo(boolean enabled) {
            this.debugInfo = enabled;
            return this;
        }

        /** Per-compilation timeout. */
        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        /** Builds the library. */
        public ShaderLibrary build() {
            Path root = cacheRoot == null ? SlangToolchain.defaultCacheRoot() : cacheRoot;
            Path cacheDirectory = cacheDir == null ? root.resolve("shader-cache") : cacheDir;
            Path workDirectory = workDir == null ? root.resolve("shader-work") : workDir;
            ShaderCompiler compiler = injectedCompiler != null
                    ? injectedCompiler
                    : SlangCompiler.detect(slangcPath, workDirectory)
                    .map(c -> {
                        SlangCompiler configured = debugInfo ? c.withDebugInfo(true) : c;
                        return timeout == null ? configured : configured.withTimeout(timeout);
                    })
                    .<ShaderCompiler>map(c -> c)
                    .orElseGet(() -> ShaderCompiler.unavailable(
                            tr("No slangc found on this system; set ") + SlangToolchain.ENV_SLANGC
                                    + tr(" or use precompiled SPIR-V")));
            return new ShaderLibrary(compiler, PrecompiledCompiler.INSTANCE,
                    new ShaderCache(cacheDirectory), threads);
        }
    }

    private static int defaultThreadCount() {
        int cpus = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(MAX_COMPILE_THREADS, Math.max(1, cpus / 2)));
    }

    // Compilation.

    /**
     * Submits asynchronously, including cache lookup, so the caller can continue recording/loading.
     * @param source shader
     * @param includeDirs extra include directories
     */
    public CompletableFuture<ShaderCompileResult> compile(ShaderSource source, Path... includeDirs) {
        return compile(source, List.of(includeDirs));
    }

    /** Submits a shader and returns immediately. */
    public CompletableFuture<ShaderCompileResult> compile(ShaderSource source,
                                                          List<Path> includeDirs) {
        Objects.requireNonNull(source, "source");
        if (closed) {
            return CompletableFuture.completedFuture(
                    ShaderCompileResult.fail(source.name(), source.stage(), source.entryPoint(),
                            tr("Shader library is closed and no longer accepts compilation requests")));
        }
        List<Path> includes = includeDirs == null ? List.of() : List.copyOf(includeDirs);
        String key = ShaderCache.keyFor(source, includes, compilerIdentityFor(source));
        submitted.incrementAndGet();

        // Deduplicate simultaneous requests for the same source.
        return inFlight.computeIfAbsent(key, k -> {
            CompletableFuture<ShaderCompileResult> future = new CompletableFuture<>();
            try {
                executor.execute(() -> {
                    ShaderCompileResult result = compileInternal(source, includes, k);
                    future.complete(result);
                });
            } catch (RuntimeException e) {
                // Handle exceptional submission states such as a closed executor.
                inFlight.remove(k);
                future.complete(ShaderCompileResult.fail(source.name(), source.stage(),
                        source.entryPoint(), tr("Failed to submit compilation task: ") + e));
            }
            future.whenComplete((result, error) -> inFlight.remove(k));
            return future;
        });
    }

    /** Submits multiple shaders at once. */
    public List<CompletableFuture<ShaderCompileResult>> compileAll(Collection<ShaderSource> sources,
                                                                   Path... includeDirs) {
        List<CompletableFuture<ShaderCompileResult>> futures = new ArrayList<>();
        for (ShaderSource source : sources) {
            futures.add(compile(source, includeDirs));
        }
        return futures;
    }

    /**
     * Waits synchronously during initialization only. Frame-loop callers should use compile and consume
     * the result when ready.
     * @param source shader
     * @param timeout wait limit
     * @param includeDirs include directories
     */
    public ShaderCompileResult compileBlocking(ShaderSource source, Duration timeout,
                                               Path... includeDirs) {
        CompletableFuture<ShaderCompileResult> future = compile(source, includeDirs);
        try {
            return timeout == null ? future.get() : future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ShaderCompileResult.fail(source.name(), source.stage(), source.entryPoint(),
                    tr("Compilation wait timed out after ") + timeout.toSeconds() + tr(" seconds. ")
                            + tr("Compilation continues in the background; query it again later."));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ShaderCompileResult.fail(source.name(), source.stage(), source.entryPoint(),
                    tr("Interrupted while waiting for compilation"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return ShaderCompileResult.fail(source.name(), source.stage(), source.entryPoint(),
                    tr("Compilation task terminated unexpectedly: ") + cause);
        }
    }

    /** Checks cache availability without compiling, allowing startup to select an immediate result or fallback. */
    public Optional<byte[]> peekCached(ShaderSource source, Path... includeDirs) {
        String key = ShaderCache.keyFor(source, List.of(includeDirs), compilerIdentityFor(source));
        return cache.lookup(key);
    }

    /** Looks up, compiles, caches and notifies listeners. */
    private ShaderCompileResult compileInternal(ShaderSource source, List<Path> includes, String key) {
        long started = System.nanoTime();
        try {
            if (source.needsCompiler()) {
                Optional<byte[]> cached = cache.lookup(key);
                if (cached.isPresent()) {
                    long elapsed = (System.nanoTime() - started) / 1_000_000L;
                    // Validate cached SPIR-V too; external modification or interrupted writes may corrupt it.
                    var validation = dev.luxloader.api.vulkan.SpirvGen.validate(cached.get());
                    if (validation.valid()) {
                        ShaderCompileResult hit = ShaderCompileResult.ok(source.name(), source.stage(),
                                source.entryPoint(), cached.get(), List.of(), elapsed, true,
                                describeCompilerFor(source));
                        stats.record(hit);
                        notifyListeners(hit);
                        return hit;
                    }
                    // Treat a corrupt entry as a miss and overwrite it after successful recompilation. Do not clear the entire cache for one truncated or externally modified file.
                }
            }

            ShaderCompiler compiler = source.needsCompiler() ? sourceCompiler : fallbackCompiler;
            ShaderCompileResult result = compiler.compile(source, includes);

            if (result.success() && source.needsCompiler()) {
                cache.store(key, result.spirv(), Map.of(
                        "name", source.name(),
                        "stage", source.stage().key(),
                        "entry", source.entryPoint(),
                        "compiler", result.usedCompiler(),
                        "elapsedMillis", Long.toString(result.elapsedMillis())));
            }
            stats.record(result);
            notifyListeners(result);
            return result;
        } catch (RuntimeException | Error e) {
            // Convert unexpected background failures to completed failure results so futures are never left unresolved.
            ShaderCompileResult failure = ShaderCompileResult.fail(source.name(), source.stage(),
                    source.entryPoint(), tr("Compilation task threw an unexpected exception: ") + e);
            stats.record(failure);
            notifyListeners(failure);
            return failure;
        }
    }

    private String describeCompilerFor(ShaderSource source) {
        return source.needsCompiler() ? sourceCompiler.describe() : fallbackCompiler.describe();
    }

    private String compilerIdentityFor(ShaderSource source) {
        return source.needsCompiler() ? sourceCompiler.cacheIdentity() : fallbackCompiler.cacheIdentity();
    }

    // State.

    /** Registers a completion callback for both success and failure. */
    public void addListener(Consumer<ShaderCompileResult> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void notifyListeners(ShaderCompileResult result) {
        for (Consumer<ShaderCompileResult> listener : listeners) {
            try {
                listener.accept(result);
            } catch (RuntimeException ignored) {
                // Listener failures must not interrupt compilation.
            }
        }
    }

    /** Pending compilation count. */
    public int pendingCount() {
        return inFlight.size();
    }

    /** Total submitted tasks. */
    public long submittedCount() {
        return submitted.get();
    }

    /** Source compiler, possibly an unavailable placeholder. */
    public ShaderCompiler sourceCompiler() {
        return sourceCompiler;
    }

    /** Disk cache. */
    public ShaderCache cache() {
        return cache;
    }

    /** Compilation statistics. */
    public Stats stats() {
        return stats;
    }

    /** Whether the library is closed. */
    public boolean isClosed() {
        return closed;
    }

    /** One-line status for loader diagnostics. */
    public String describe() {
        return tr("Shader library[") + sourceCompiler.describe()
                + tr(", worker threads ") + ((java.util.concurrent.ThreadPoolExecutor) executor).getCorePoolSize()
                + tr(", cache ") + cache.size() + tr(" entries, ")
                + (closed ? tr("closed") : tr("compiling ") + pendingCount()) + "]";
    }

    /** Waits for active compilations and releases the executor. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        sourceCompiler.close();
    }

    /**
     * Compilation statistics separate real compilations from cache hits: both deliver SPIR-V, but only the
     * former incurs compilation cost.
     */
    public static final class Stats {
        private final AtomicInteger succeeded = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger cacheHits = new AtomicInteger();
        private final AtomicLong totalMillis = new AtomicLong();

        void record(ShaderCompileResult result) {
            if (result.success()) {
                if (result.fromCache()) {
                    cacheHits.incrementAndGet();
                } else {
                    succeeded.incrementAndGet();
                }
            } else {
                failed.incrementAndGet();
            }
            totalMillis.addAndGet(result.elapsedMillis());
        }

        /** Successful real compilations, excluding cache hits. */
        public int succeeded() {
            return succeeded.get();
        }

        /** Failure count. */
        public int failed() {
            return failed.get();
        }

        /** Cache hit count. */
        public int cacheHits() {
            return cacheHits.get();
        }

        /** Successful deliveries, including cache hits. */
        public int delivered() {
            return succeeded.get() + cacheHits.get();
        }

        /** Cumulative milliseconds including cache reads. */
        public long totalMillis() {
            return totalMillis.get();
        }

        /** Cache hit rate, zero before any deliveries. */
        public double cacheHitRatio() {
            int total = delivered();
            return total == 0 ? 0.0 : (double) cacheHits.get() / total;
        }

        /** One-line summary. */
        public String describe() {
            return tr("Compiled ") + succeeded() + tr(", cache hits ") + cacheHits()
                    + tr(", failures ") + failed() + tr(", total time ") + totalMillis() + " ms";
        }
    }

    /** Named daemon compiler threads for readable thread dumps. */
    private static final class CompileThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "luxloader-shader-compile-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
