package com.knowagent.observability.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.application.service.ErrorMessageSanitizer;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One transactional outbox event to be published to the broker by a competing
 * publisher. Immutable: every state transition returns a new instance with a
 * bumped {@link #version()}, so persistence can guard each write with the
 * previous version plus the previous status.
 */
public record OutboxEvent(
        UUID id,
        TenantId tenantId,
        String aggregateType,
        String aggregateId,
        String eventType,
        JsonNode payload,
        JsonNode headers,
        OutboxStatus status,
        int retryCount,
        int maxRetries,
        Instant nextRetryAt,
        String lockedBy,
        Instant lockedUntil,
        String lastError,
        Instant publishedAt,
        long version,
        Instant createdAt) {

    public OutboxEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        requireText(eventType, "eventType");
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        if (headers != null && !headers.isObject()) {
            throw new IllegalArgumentException("headers must be a JSON object");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (retryCount < 0 || maxRetries < 1 || retryCount > maxRetries) {
            throw new IllegalArgumentException("retryCount must be in [0, maxRetries] with maxRetries >= 1");
        }
        Objects.requireNonNull(nextRetryAt, "nextRetryAt must not be null");
        if ((lockedBy == null) != (lockedUntil == null)) {
            throw new IllegalArgumentException("lockedBy and lockedUntil must both be set or both be null");
        }
        if (status == OutboxStatus.PUBLISHED && publishedAt == null) {
            throw new IllegalArgumentException("publishedAt must be set when status is PUBLISHED");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        lastError = ErrorMessageSanitizer.sanitize(lastError);
    }

    /** Claims this event: PROCESSING, a fresh lease, and the next version. */
    public OutboxEvent claimed(String workerId, Instant now, Duration lease) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(lease, "lease must not be null");
        return new OutboxEvent(id, tenantId, aggregateType, aggregateId, eventType, payload, headers,
                OutboxStatus.PROCESSING, retryCount, maxRetries, nextRetryAt, workerId, now.plus(lease),
                lastError, publishedAt, version + 1, createdAt);
    }

    /** Marks this event published: PUBLISHED, publishedAt set, lease cleared. */
    public OutboxEvent published(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return new OutboxEvent(id, tenantId, aggregateType, aggregateId, eventType, payload, headers,
                OutboxStatus.PUBLISHED, retryCount, maxRetries, nextRetryAt, null, null, lastError, now,
                version + 1, createdAt);
    }

    /**
     * Records a failed delivery attempt. The error text is sanitized (redacted,
     * control characters stripped, truncated) by
     * {@link com.knowagent.observability.application.service.ErrorMessageSanitizer}
     * in this record's constructor - the single shared sanitization boundary for Task
     * and Outbox error text - so a raw message never reaches {@code last_error}.
     * When the retry budget is exhausted the event goes to {@code DEAD_LETTER};
     * otherwise it returns to {@code PENDING} with an exponential-backoff
     * {@code next_retry_at}.
     */
    public OutboxEvent failure(String rawError, Instant now, RetryPolicy policy) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        int nextRetries = retryCount + 1;
        boolean dead = nextRetries >= maxRetries;
        OutboxStatus nextStatus = dead ? OutboxStatus.DEAD_LETTER : OutboxStatus.PENDING;
        Instant next = dead ? now : policy.nextRetryAt(now, nextRetries);
        return new OutboxEvent(id, tenantId, aggregateType, aggregateId, eventType, payload, headers,
                nextStatus, nextRetries, maxRetries, next, null, null, rawError, publishedAt,
                version + 1, createdAt);
    }

    /**
     * Deliberately omits {@code payload}, {@code headers} and {@code lastError} (rule:
     * secrets / raw content never surface in {@code toString}).
     */
    @Override
    public String toString() {
        return "OutboxEvent[id=" + id + ", tenantId=" + tenantId
                + ", aggregateType=" + aggregateType + ", aggregateId=" + aggregateId
                + ", eventType=" + eventType + ", status=" + status
                + ", retryCount=" + retryCount + ", maxRetries=" + maxRetries
                + ", nextRetryAt=" + nextRetryAt + ", lockedBy=" + lockedBy
                + ", lockedUntil=" + lockedUntil + ", publishedAt=" + publishedAt
                + ", version=" + version + ", createdAt=" + createdAt + "]";
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
