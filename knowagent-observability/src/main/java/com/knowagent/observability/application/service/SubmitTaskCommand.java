package com.knowagent.observability.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.common.tenant.TenantId;

import java.util.Objects;

/**
 * Asynchronous-work submission: one task row and one transactional outbox event,
 * both written atomically with the caller's business record.
 *
 * <p>Both JSONB payloads must be JSON objects (the database CHECK enforces it too);
 * {@code idempotencyKey} is optional and, when set, is part of the unique index
 * {@code uq_tasks_idempotency (tenant_id, task_type, idempotency_key)}.
 */
public record SubmitTaskCommand(
        TenantId tenantId,
        String taskType,
        String aggregateType,
        String aggregateId,
        String idempotencyKey,
        JsonNode taskPayload,
        int maxAttempts,
        String eventType,
        JsonNode eventPayload,
        JsonNode eventHeaders,
        int eventMaxRetries) {

    public SubmitTaskCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
    }

    /**
     * Deliberately omits {@code taskPayload}, {@code eventPayload} and
     * {@code eventHeaders} (rule: secrets / raw content never surface in
     * {@code toString}).
     */
    @Override
    public String toString() {
        return "SubmitTaskCommand[tenantId=" + tenantId
                + ", taskType=" + taskType + ", aggregateType=" + aggregateType
                + ", aggregateId=" + aggregateId + ", idempotencyKey=" + idempotencyKey
                + ", maxAttempts=" + maxAttempts + ", eventType=" + eventType
                + ", eventMaxRetries=" + eventMaxRetries + "]";
    }
}
