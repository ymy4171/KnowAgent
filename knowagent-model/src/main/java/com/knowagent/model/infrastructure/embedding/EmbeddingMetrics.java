package com.knowagent.model.infrastructure.embedding;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Non-sensitive embedding call metrics. Records only the provider id, model name,
 * outcome, call duration, batch count and estimated tokens - never chunk text, never
 * embedding values and never a secret (prompt requirement 9). When no
 * {@link MeterRegistry} is available (for example the worker without actuator) all
 * methods are no-ops.
 */
final class EmbeddingMetrics {

    enum Outcome {
        SUCCESS,
        AUTH_FAILED,
        RATE_LIMITED,
        TIMEOUT,
        BAD_RESPONSE,
        SERVICE_ERROR,
        CONFIGURATION_ERROR
    }

    private final MeterRegistry registry;

    EmbeddingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordCall(UUID providerId, String model, Outcome outcome, long durationNanos,
                    int batchCount, long estimatedTokens) {
        if (registry == null) {
            return;
        }
        String provider = String.valueOf(providerId);
        String outcomeTag = outcome.name().toLowerCase(Locale.ROOT);
        Timer.builder("knowagent.model.embedding.calls")
                .tag("provider", provider)
                .tag("model", model)
                .tag("outcome", outcomeTag)
                .register(registry)
                .record(Duration.ofNanos(durationNanos));
        if (batchCount > 0) {
            Counter.builder("knowagent.model.embedding.batches")
                    .tag("provider", provider)
                    .tag("model", model)
                    .register(registry)
                    .increment(batchCount);
        }
        if (estimatedTokens > 0) {
            Counter.builder("knowagent.model.embedding.estimated_tokens")
                    .tag("provider", provider)
                    .tag("model", model)
                    .register(registry)
                    .increment(estimatedTokens);
        }
    }
}
