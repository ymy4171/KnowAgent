package com.knowagent.observability.inbox;

import com.knowagent.common.tenant.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A successful consumer receipt used for idempotency. The unique constraint
 * {@code uq_inbox_events_consumer_event (consumer_name, event_id)} is the source of
 * truth: recording the same event twice is a no-op, never an error, so a replayed
 * broker message does not execute the business side effect twice.
 */
public record InboxEvent(
        UUID id,
        TenantId tenantId,
        String consumerName,
        UUID eventId,
        String eventType,
        String payloadHash,
        Instant processedAt) {

    private static final String HASH_PATTERN = "^[0-9a-f]{64}$";

    public InboxEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName must not be blank");
        }
        Objects.requireNonNull(eventId, "eventId must not be null");
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (payloadHash != null && !payloadHash.matches(HASH_PATTERN)) {
            throw new IllegalArgumentException("payloadHash must be a 64-char lowercase hex digest");
        }
        Objects.requireNonNull(processedAt, "processedAt must not be null");
    }
}
