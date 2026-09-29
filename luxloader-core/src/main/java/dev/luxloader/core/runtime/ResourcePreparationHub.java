package dev.luxloader.core.runtime;

import dev.luxloader.api.resource.*;
import dev.luxloader.api.resource.ResourcePreparationService.*;
import dev.luxloader.api.scene.ResourceAccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Owns bounded CPU workers. Minecraft lookup and GPU work never run on these workers. */
public final class ResourcePreparationHub implements AutoCloseable {
    public static final Limits DEFAULT_LIMITS = new Limits(2, 32, 32, 4096, 8,
            16 * 1024 * 1024, 32 * 1024 * 1024, 64L * 1024 * 1024, 256L * 1024 * 1024);
    private final Supplier<ResourceAccess> source;
    private final Limits limits;
    private final ThreadPoolExecutor workers;
    private final Semaphore workSlots;
    private final Set<ScopeImpl> scopes = new HashSet<>();
    private ResourceState state = new ResourceState(Long.MIN_VALUE, ResourceState.Phase.CLOSED);
    private long world, submitted, completed, cancelled, rejected, failed, inputRead, largestFile;
    private long decoded, peakDecoded, inputInUse, peakInput;
    private long streamCleanupFailures;
    private boolean closed;

    public ResourcePreparationHub(Supplier<ResourceAccess> source) { this(source, DEFAULT_LIMITS); }

    public ResourcePreparationHub(Supplier<ResourceAccess> source, Limits limits) {
        this.source = Objects.requireNonNull(source);
        this.limits = Objects.requireNonNull(limits);
        workSlots = new Semaphore(limits.workers() + limits.queuedTasks());
        workers = new ThreadPoolExecutor(limits.workers(), limits.workers(), 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(limits.queuedTasks()), runnable -> {
                    Thread thread = new Thread(runnable, "luxloader-resource-worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    public ResourcePreparationService serviceForOwner(long owner) {
        return () -> {
            refresh();
            synchronized (this) {
                if (closed) throw new IllegalStateException("Resource preparation closed");
                if (scopes.size() >= limits.maxScopes()) throw new IllegalStateException("Resource scope limit exceeded");
                ScopeImpl scope = new ScopeImpl(owner);
                scopes.add(scope);
                return scope;
            }
        };
    }

    /** Host-thread safe point, before scene/atlas acquisition and before accepting decoder results. */
    public void refresh() {
        ResourceState observed = source.get().state();
        List<ScopeImpl> invalidate;
        synchronized (this) {
            if (observed.equals(state)) return;
            state = observed;
            invalidate = List.copyOf(scopes);
        }
        invalidate.forEach(ScopeImpl::invalidate);
    }

    /** Session generation is supplied even when there are no client-state subscribers. */
    public void worldSession(long generation) {
        List<ScopeImpl> invalidate;
        synchronized (this) {
            if (world == generation) return;
            world = generation;
            invalidate = List.copyOf(scopes);
        }
        invalidate.forEach(ScopeImpl::invalidate);
    }

    public void releaseOwner(long owner) {
        List<ScopeImpl> owned;
        synchronized (this) { owned = scopes.stream().filter(scope -> scope.owner == owner).toList(); }
        owned.forEach(ScopeImpl::close);
    }

    /** A rendering pipeline cannot leave preparation scopes alive after its GPU resources retire. */
    public void closeScopes() {
        List<ScopeImpl> all;
        synchronized (this) { all = List.copyOf(scopes); }
        all.forEach(ScopeImpl::close);
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        closeScopes();
        workers.shutdownNow();
    }

    public synchronized Metrics metrics() {
        return new Metrics(submitted, completed, cancelled, rejected, failed,
                workers.getQueue().size(), workers.getActiveCount(), inputRead, largestFile,
                decoded, peakDecoded, peakInput, streamCleanupFailures);
    }

    private synchronized Generation generation() { return new Generation(state.generation(), world); }

    private final class ScopeImpl implements Scope {
        final long owner;
        final Thread hostThread = Thread.currentThread();
        final Set<Job<?>> jobs = new HashSet<>();
        volatile boolean scopeClosed;
        ScopeImpl(long owner) { this.owner = owner; }

        private void hostThread() {
            if (Thread.currentThread() != hostThread) throw new IllegalStateException("Resource scope requires its host thread");
        }
        private void refreshHost() { hostThread(); refresh(); }
        @Override public Generation generation() { refreshHost(); return ResourcePreparationHub.this.generation(); }
        @Override public ResourceState state() { refreshHost(); synchronized (ResourcePreparationHub.this) { return state; } }
        @Override public Limits limits() { return limits; }
        @Override public Metrics metrics() { return ResourcePreparationHub.this.metrics(); }
        @Override public boolean isClosed() { return scopeClosed; }

        @Override public <T> Task<T> submit(List<ResourceKey> dependencies, Decoder<T> decoder) {
            refreshHost();
            Objects.requireNonNull(decoder, "decoder");
            List<ResourceKey> keys = List.copyOf(new LinkedHashSet<>(dependencies));
            if (keys.size() > limits.keysPerTask()) throw new IllegalArgumentException("Too many resource dependencies");
            Job<T> job = new Job<>(this, ResourcePreparationHub.this.generation(), keys, decoder);
            synchronized (ResourcePreparationHub.this) {
                if (scopeClosed || closed) return job.reject(Failure.CLOSED);
                if (!state.ready()) return job.reject(Failure.UNAVAILABLE);
                if (jobs.size() >= limits.maxTasksPerScope() || !workSlots.tryAcquire()) return job.reject(Failure.BACKPRESSURE);
                job.hasSlot = true;
                jobs.add(job);
                submitted++;
            }
            try {
                ResourceAccess access = source.get();
                for (ResourceKey key : keys) {
                    if (job.cancelled()) break;
                    // Capture the pack winner on this thread. Only the standard stream crosses to a worker.
                    Optional<InputStream> stream = access.open(key);
                    if (stream.isPresent()) job.addStream(key, stream.get());
                    refresh();
                    if (!job.current()) job.close();
                }
                if (!job.cancelled()) workers.execute(job);
                else job.finishWork();
            } catch (RejectedExecutionException e) {
                job.fail(Failure.BACKPRESSURE, e);
                job.finishWork();
            } catch (Exception | LinkageError e) {
                job.fail(Failure.IO, e);
                job.finishWork();
            }
            return job;
        }

        @Override public void invalidate() {
            List<Job<?>> old;
            synchronized (ResourcePreparationHub.this) { old = List.copyOf(jobs); }
            old.forEach(Job::close);
        }
        @Override public void close() {
            synchronized (ResourcePreparationHub.this) {
                if (scopeClosed) return;
                scopeClosed = true;
                scopes.remove(this);
            }
            invalidate();
        }
    }

    private final class Job<T> implements Task<T>, Runnable {
        final ScopeImpl scope;
        final Generation generation;
        final List<ResourceKey> keys;
        final Decoder<T> decoder;
        final Map<ResourceKey, InputStream> streams = new LinkedHashMap<>();
        final AtomicBoolean workFinished = new AtomicBoolean();
        volatile Status status = Status.PENDING;
        volatile Failure failure = Failure.NONE;
        volatile String detail = "";
        volatile T result;
        boolean hasSlot, running;
        long reserved, retained = -1;

        Job(ScopeImpl scope, Generation generation, List<ResourceKey> keys, Decoder<T> decoder) {
            this.scope = scope; this.generation = generation; this.keys = keys; this.decoder = decoder;
        }
        Job<T> reject(Failure reason) {
            status = Status.REJECTED; failure = reason; rejected++; workFinished.set(true); return this;
        }
        synchronized void addStream(ResourceKey key, InputStream stream) {
            if (cancelled()) closeStream(stream); else streams.put(key, stream);
        }
        synchronized void closeStreams() {
            var owned = List.copyOf(streams.values());
            streams.clear();
            owned.forEach(this::closeStream);
        }
        void closeStream(InputStream stream) {
            try { stream.close(); }
            catch (IOException | RuntimeException | LinkageError error) {
                synchronized (ResourcePreparationHub.this) { streamCleanupFailures++; }
                detail = "Resource stream cleanup failed: " + error;
            }
        }
        boolean cancelled() { return status == Status.CANCELLED || scope.scopeClosed || Thread.currentThread().isInterrupted(); }
        boolean current() {
            synchronized (ResourcePreparationHub.this) {
                return !scope.scopeClosed && !closed && state.ready()
                        && generation.equals(ResourcePreparationHub.this.generation());
            }
        }
        void poll() {
            scope.refreshHost();
            if (!current() && status != Status.REJECTED) close();
        }
        @Override public Generation generation() { return generation; }
        @Override public List<ResourceKey> dependencies() { return keys; }
        @Override public Status status() { poll(); return status; }
        @Override public Failure failure() { poll(); return failure; }
        @Override public String detail() { return detail; }
        @Override public Optional<T> result() { poll(); return status == Status.READY ? Optional.ofNullable(result) : Optional.empty(); }

        @Override public void run() {
            synchronized (this) { running = true; }
            long inputBytes = 0;
            boolean decoding = false;
            try {
                if (cancelled() || !current()) { close(); return; }
                Map<ResourceKey, InputStream> opened;
                synchronized (this) { opened = new LinkedHashMap<>(streams); }
                Map<ResourceKey, byte[]> contents = new HashMap<>();
                for (var entry : opened.entrySet()) {
                    byte[] bytes = readBounded(entry.getValue(), Math.min(limits.fileBytes(), limits.inputBytesPerTask() - (int) inputBytes));
                    inputBytes += bytes.length;
                    synchronized (ResourcePreparationHub.this) {
                        inputInUse += bytes.length; peakInput = Math.max(peakInput, inputInUse);
                        inputRead += bytes.length; largestFile = Math.max(largestFile, bytes.length);
                    }
                    contents.put(entry.getKey(), bytes);
                }
                closeStreams();
                if (cancelled() || !current()) { close(); return; }
                decoding = true;
                T value = Objects.requireNonNull(decoder.decode(new Input() {
                    @Override public Optional<ByteBuffer> bytes(ResourceKey key) {
                        if (!keys.contains(key)) throw new IllegalArgumentException("Undeclared dependency: " + key);
                        return Optional.ofNullable(contents.get(key)).map(bytes -> ByteBuffer.wrap(bytes).asReadOnlyBuffer());
                    }
                    @Override public Limits limits() { return limits; }
                    @Override public void reserveDecodedBytes(long bytes) { reserve(bytes); }
                    @Override public void retainDecodedBytes(long bytes) {
                        synchronized (Job.this) {
                            if (bytes < 0 || bytes > reserved) throw new IllegalArgumentException("Retained bytes exceed the reserved peak");
                            retained = bytes;
                        }
                    }
                    @Override public boolean cancelled() { return Job.this.cancelled(); }
                }), "Decoder returned null");
                synchronized (this) {
                    if (cancelled() || !current()) { close(); return; }
                    result = value;
                    status = Status.READY;
                    synchronized (ResourcePreparationHub.this) { completed++; }
                }
            } catch (LimitExceededException e) { fail(Failure.LIMIT, e);
            } catch (IOException e) { fail(decoding ? Failure.DECODE : Failure.IO, e);
            } catch (Exception | LinkageError e) { fail(Failure.DECODE, e);
            } finally {
                synchronized (ResourcePreparationHub.this) { inputInUse -= inputBytes; }
                finishWork();
            }
        }

        private byte[] readBounded(InputStream stream, int limit) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(8192, Math.max(0, limit)));
            byte[] block = new byte[8192];
            for (;;) {
                if (cancelled()) throw new IOException("Resource work cancelled");
                int count = stream.read(block, 0, Math.min(block.length, limit - bytes.size() + 1));
                if (count < 0) return bytes.toByteArray();
                if (bytes.size() + count > limit) throw new LimitException("Resource input byte limit exceeded");
                bytes.write(block, 0, count);
            }
        }
        private synchronized void reserve(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("Negative decoded allocation");
            synchronized (ResourcePreparationHub.this) {
                if (cancelled() || !current()) throw new CancellationException();
                if (bytes > limits.decodedBytesPerTask() - reserved || bytes > limits.decodedBytesTotal() - decoded)
                    throw new LimitException("Decoded resource memory limit exceeded");
                reserved += bytes; decoded += bytes; peakDecoded = Math.max(peakDecoded, decoded);
            }
        }
        synchronized void fail(Failure reason, Throwable error) {
            if (status != Status.PENDING) return;
            status = Status.FAILED; failure = reason; detail = error.toString();
            synchronized (ResourcePreparationHub.this) { failed++; }
        }
        void finishWork() {
            if (!workFinished.compareAndSet(false, true)) return;
            closeStreams();
            synchronized (this) {
                if (hasSlot) { hasSlot = false; workSlots.release(); }
                if (status != Status.READY) releaseReservation();
                else if (retained >= 0) {
                    synchronized (ResourcePreparationHub.this) { decoded -= reserved - retained; reserved = retained; }
                }
            }
        }
        private void releaseReservation() {
            synchronized (ResourcePreparationHub.this) { decoded -= reserved; reserved = 0; }
        }
        @Override public synchronized void close() {
            if (status == Status.CANCELLED) return;
            status = Status.CANCELLED; failure = Failure.STALE; result = null;
            synchronized (ResourcePreparationHub.this) { cancelled++; scope.jobs.remove(this); }
            workers.remove(this);
            closeStreams();
            // A non-cooperative decoder remains charged until its worker actually exits.
            if (!running) finishWork();
            if (workFinished.get()) releaseReservation();
        }
    }

    private static final class LimitException extends LimitExceededException {
        LimitException(String message) { super(message); }
    }
}
