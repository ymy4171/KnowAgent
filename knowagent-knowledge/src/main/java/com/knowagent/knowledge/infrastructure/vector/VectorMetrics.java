package com.knowagent.knowledge.infrastructure.vector;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Non-sensitive vector-store metrics. Records the collection, the operation
 * (upsert/search/delete/init), the outcome and the duration per call, plus the
 * entity count (rows/hits/deletes) per successful operation. Never records vector
 * content, chunk text, ids or any query payload (Rule 8). Without a
 * {@link MeterRegistry} every method is a no-op, so the adapter works in contexts
 * that do not export metrics (mirrors the model module's EmbeddingMetrics).
 */
public final class VectorMetrics {

    public enum Outcome {
        SUCCESS,
        FAILURE
    }

    private static final String OPERATIONS = "knowagent.vector.operations";
    private static final String ENTITIES = "knowagent.vector.entities";
    private static final String DURATION = "knowagent.vector.duration";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> operationCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> entityCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

    public VectorMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One call outcome: increments the operation counter and records the duration. */
    public void recordCall(String collection, String operation, Outcome outcome, Duration duration) {
        if (registry == null) {
            return;
        }
        String outcomeTag = outcome.name().toLowerCase(Locale.ROOT);
        Counter operations = operationCounters.computeIfAbsent(
                key(collection, operation, outcomeTag),
                ignored -> Counter.builder(OPERATIONS)
                        .tag("collection", collection)
                        .tag("operation", operation)
                        .tag("outcome", outcomeTag)
                        .register(registry));
        operations.increment();

        Timer timer = timers.computeIfAbsent(
                key(collection, operation, "timer"),
                ignored -> Timer.builder(DURATION)
                        .tag("collection", collection)
                        .tag("operation", operation)
                        .register(registry));
        timer.record(duration);
    }

    /** Entity count of a successful operation (upserted rows, search hits, deleted rows). */
    public void recordEntities(String collection, String operation, long count) {
        if (registry == null || count <= 0) {
            return;
        }
        Counter entities = entityCounters.computeIfAbsent(
                key(collection, operation, "count"),
                ignored -> Counter.builder(ENTITIES)
                        .tag("collection", collection)
                        .tag("operation", operation)
                        .register(registry));
        entities.increment(count);
    }

    private static String key(String... parts) {
        return String.join("|", parts);
    }
}
