package dev.luxloader.api.resource;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

/**
 * Bounded CPU resource preparation for one plugin instance. Create a scope during pipeline
 * initialization and close it with the pipeline. The host also closes scopes at pipeline teardown,
 * cancels their tasks on world/resource changes, and closes them on plugin unload.
 *
 * <p>Open scopes, submit, poll and publish on the host render/update thread only. The decoder executes
 * on a bounded worker and may use only its immutable captured values and {@link Input}; it must not
 * call Minecraft, GPU, host services, or other thread-confined APIs. Source lookup/stream acquisition
 * happens during submit on the calling thread; stream reads and decoding happen on the worker. An
 * older ResourceAccess implementation may acquire its stream by doing a synchronous legacy read.
 *
 * <p>A task owns its decoded result until close/cancellation. Consumers must discard retained result
 * references when the task is invalidated. This service does not upload GPU data: validate the task's
 * generation again at the render-thread publication boundary. R1 invalidates the whole resource
 * generation; declared dependencies are diagnostic facts, not a promise of selective invalidation.
 */
public interface ResourcePreparationService {
    ResourcePreparationService UNAVAILABLE = () -> { throw new UnsupportedOperationException("Resource preparation unavailable"); };

    Scope openScope();

    record Generation(long resource, long world) { }

    /** Fixed host safety ceilings; a decoder must reserve its peak decoded allocation before allocating. */
    record Limits(int workers, int queuedTasks, int maxScopes, int maxTasksPerScope, int keysPerTask,
                  int fileBytes, int inputBytesPerTask, long decodedBytesPerTask, long decodedBytesTotal) {
        public Limits {
            if (workers < 1 || queuedTasks < 1 || maxScopes < 1 || maxTasksPerScope < 1 || keysPerTask < 1
                    || fileBytes < 1 || inputBytesPerTask < fileBytes || decodedBytesPerTask < 1
                    || decodedBytesTotal < decodedBytesPerTask) throw new IllegalArgumentException("Invalid resource limits");
        }
    }

    record Metrics(long submitted, long completed, long cancelled, long rejected, long failed,
                   int queued, int running, long inputBytesRead, long largestFileBytes,
                   long decodedBytes, long peakDecodedBytes, long peakInputBytes, long streamCleanupFailures) { }

    interface Scope extends AutoCloseable {
        Generation generation();
        ResourceState state();
        Limits limits();
        Metrics metrics();
        boolean isClosed();
        <T> Task<T> submit(List<ResourceKey> dependencies, Decoder<T> decoder);
        /** Cancels pending and completed tasks while retaining the scope for a new candidate. */
        void invalidate();
        @Override void close();
    }

    enum Status { PENDING, READY, FAILED, CANCELLED, REJECTED }
    enum Failure { NONE, UNAVAILABLE, IO, DECODE, LIMIT, STALE, CLOSED, BACKPRESSURE }

    /** A plugin decoder can reject its format-specific size limits before allocation. */
    class LimitExceededException extends RuntimeException {
        public LimitExceededException(String detail) { super(detail); }
    }

    interface Task<T> extends AutoCloseable {
        Generation generation();
        List<ResourceKey> dependencies();
        Status status();
        Failure failure();
        String detail();
        /** Empty until complete, and again after cancellation or a world/resource generation change. */
        Optional<T> result();
        @Override void close();
    }

    @FunctionalInterface interface Decoder<T> { T decode(Input input) throws Exception; }

    interface Input {
        /** Read-only bytes owned by this task; missing is distinct from acquisition or decode failure. */
        Optional<ByteBuffer> bytes(ResourceKey key);
        Limits limits();
        /** Reserve peak output/scratch memory before allocation, including decoder-owned copies. */
        void reserveDecodedBytes(long bytes);
        /**
         * Declare bytes retained by the returned result (at most the reserved peak). Scratch remains
         * charged until the worker exits. Without this declaration the full reservation stays charged.
         */
        void retainDecodedBytes(long bytes);
        /** Cooperative cancellation, also checked by the host after the decoder returns. */
        boolean cancelled();
    }
}
