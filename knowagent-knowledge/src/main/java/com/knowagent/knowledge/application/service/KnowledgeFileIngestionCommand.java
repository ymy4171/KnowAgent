package com.knowagent.knowledge.application.service;

import com.knowagent.common.tenant.TenantId;

import java.util.Objects;
import java.util.UUID;

/** Trusted command reconstructed from a validated Redis event envelope. */
public record KnowledgeFileIngestionCommand(
        TenantId tenantId,
        UUID eventId,
        String consumerName,
        UUID fileId,
        String payloadHash) {

    public KnowledgeFileIngestionCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName must not be blank");
        }
        Objects.requireNonNull(fileId, "fileId must not be null");
        if (payloadHash == null || !payloadHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("payloadHash must be a lowercase SHA-256 digest");
        }
    }
}
