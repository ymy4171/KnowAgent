package com.knowagent.worker.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.common.tenant.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned, non-secret Redis delivery envelope. */
public record IngestionEventEnvelope(
        UUID eventId,
        String eventType,
        TenantId tenantId,
        UUID aggregateId,
        Instant occurredAt,
        int schemaVersion,
        JsonNode payload,
        String payloadHash) {

    public IngestionEventEnvelope {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(aggregateId);
        Objects.requireNonNull(occurredAt);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("payload must be an object");
        }
        if (payloadHash == null || !payloadHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("payloadHash must be a lowercase SHA-256 digest");
        }
        payload = payload.deepCopy();
    }

    public UUID fileId() {
        return UUID.fromString(payload.path("file_id").asText());
    }
}
