package com.knowagent.knowledge.infrastructure.vector;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the non-sensitive metric contract: collection/operation/outcome tags and
 * entity counts are recorded under stable names, and a missing MeterRegistry
 * degrades to a no-op (no vectors, chunk text or ids are ever recorded, Rule 8).
 */
class VectorMetricsTest {

    @Test
    void recordsCallsAndEntityCountsWithStableTags() {
        MeterRegistry registry = new SimpleMeterRegistry();
        VectorMetrics metrics = new VectorMetrics(registry);

        metrics.recordCall("knowledge_chunks", "upsert", VectorMetrics.Outcome.SUCCESS, Duration.ofMillis(12));
        metrics.recordCall("knowledge_chunks", "upsert", VectorMetrics.Outcome.SUCCESS, Duration.ofMillis(9));
        metrics.recordCall("knowledge_chunks", "search", VectorMetrics.Outcome.FAILURE, Duration.ofMillis(3));
        metrics.recordEntities("knowledge_chunks", "upsert", 5);
        metrics.recordEntities("knowledge_chunks", "search", 2);

        assertThat(registry.get("knowagent.vector.operations")
                .tag("collection", "knowledge_chunks")
                .tag("operation", "upsert")
                .tag("outcome", "success").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("knowagent.vector.operations")
                .tag("collection", "knowledge_chunks")
                .tag("operation", "search")
                .tag("outcome", "failure").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("knowagent.vector.entities")
                .tag("collection", "knowledge_chunks")
                .tag("operation", "upsert").counter().count()).isEqualTo(5.0);
        assertThat(registry.get("knowagent.vector.entities")
                .tag("collection", "knowledge_chunks")
                .tag("operation", "search").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("knowagent.vector.duration")
                .tag("collection", "knowledge_chunks")
                .tag("operation", "upsert").timer().count()).isEqualTo(2);
    }

    @Test
    void withoutARegistryEveryCallIsANoOp() {
        VectorMetrics metrics = new VectorMetrics(null);
        metrics.recordCall("c", "search", VectorMetrics.Outcome.SUCCESS, Duration.ofSeconds(1));
        metrics.recordEntities("c", "search", 100);
    }

    @Test
    void entityCountsAreOnlyRecordedWhenPositive() {
        MeterRegistry registry = new SimpleMeterRegistry();
        VectorMetrics metrics = new VectorMetrics(registry);
        metrics.recordEntities("c", "delete", 0);
        assertThat(registry.find("knowagent.vector.entities").counters()).isEmpty();
    }
}
