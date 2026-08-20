package com.knowagent.knowledge.infrastructure.retrieval;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeRetrievalObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Retrieval metrics containing only non-sensitive identifiers, counts and timing.
 * Query text, returned content, file/chunk ids and vectors are never tags or values.
 */
@Component
public final class MicrometerKnowledgeRetrievalObserver implements KnowledgeRetrievalObserver {

    private static final String CALLS = "knowagent.knowledge.retrieval.calls";
    private static final String CANDIDATES = "knowagent.knowledge.retrieval.candidates";
    private static final String RESULTS = "knowagent.knowledge.retrieval.results";
    private static final String DURATION = "knowagent.knowledge.retrieval.duration";

    private final MeterRegistry registry;

    public MicrometerKnowledgeRetrievalObserver(ObjectProvider<MeterRegistry> registries) {
        this.registry = Objects.requireNonNull(registries, "registries must not be null").getIfAvailable();
    }

    @Override
    public void record(TenantId tenantId, UUID providerId, String outcome,
                       int candidateCount, int resultCount, Duration duration) {
        if (registry == null) {
            return;
        }
        String tenant = tenantId == null ? "unknown" : tenantId.value().toString();
        String provider = providerId == null ? "unresolved" : providerId.toString();
        String safeOutcome = outcome == null || outcome.isBlank() ? "unknown" : outcome;
        Counter.builder(CALLS)
                .tags("tenant_id", tenant, "provider_id", provider, "outcome", safeOutcome)
                .register(registry)
                .increment();
        if (candidateCount > 0) {
            Counter.builder(CANDIDATES)
                    .tags("tenant_id", tenant, "provider_id", provider)
                    .register(registry)
                    .increment(candidateCount);
        }
        if (resultCount > 0) {
            Counter.builder(RESULTS)
                    .tags("tenant_id", tenant, "provider_id", provider)
                    .register(registry)
                    .increment(resultCount);
        }
        Timer.builder(DURATION)
                .tags("tenant_id", tenant, "provider_id", provider, "outcome", safeOutcome)
                .register(registry)
                .record(duration);
    }
}
