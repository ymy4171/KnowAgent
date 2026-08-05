package com.knowagent.agent.job;

import com.knowagent.common.tenant.TenantId;

import java.time.Instant;
import java.util.UUID;

public record JobEnvelope(
        UUID jobId,
        TenantId tenantId,
        String jobType,
        String aggregateId,
        String payload,
        Instant createdAt
) {
}

