package com.knowagent.knowledge.application.port.out;

import com.knowagent.common.tenant.TenantId;

import java.time.Duration;
import java.util.UUID;

/** Non-sensitive observability boundary for semantic retrieval calls. */
public interface KnowledgeRetrievalObserver {

    void record(TenantId tenantId, UUID providerId, String outcome,
                int candidateCount, int resultCount, Duration duration);
}
