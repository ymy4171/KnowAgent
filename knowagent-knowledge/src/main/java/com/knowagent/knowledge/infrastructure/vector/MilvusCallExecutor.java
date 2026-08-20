package com.knowagent.knowledge.infrastructure.vector;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs every Milvus SDK call on a dedicated executor with a hard per-operation
 * timeout (connect/search/write/delete/init are configured independently, Rule 12).
 * A timeout or SDK failure is mapped to the stable {@link VectorStoreException}
 * contract and metrics record the call outcome and duration - never the payload.
 *
 * <p>The underlying gRPC call may keep running after a timeout fires; its result is
 * discarded. The executor is shared by all operations and closed with the adapter.
 */
final class MilvusCallExecutor implements AutoCloseable {

    private final ScheduledExecutorService executor;
    private final VectorMetrics metrics;

    MilvusCallExecutor(VectorMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "knowagent-milvus-call");
            thread.setDaemon(true);
            return thread;
        });
    }

    <T> T call(String collection, String operation, Duration timeout, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            T result = CompletableFuture.supplyAsync(action, executor)
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            metrics.recordCall(collection, operation, VectorMetrics.Outcome.SUCCESS, elapsed(started));
            return result;
        } catch (Exception failure) {
            metrics.recordCall(collection, operation, VectorMetrics.Outcome.FAILURE, elapsed(started));
            throw MilvusErrorMapper.map(failure);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
