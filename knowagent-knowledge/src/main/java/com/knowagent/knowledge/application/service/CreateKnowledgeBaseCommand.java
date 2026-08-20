package com.knowagent.knowledge.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.chunk.ChunkPolicy;
import com.knowagent.knowledge.knowledgebase.KnowledgeType;
import com.knowagent.knowledge.knowledgebase.RetrievalConfig;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable create command. Nullable provider/model fields must be supplied together;
 * the service validates that the referenced providers belong to the tenant, are
 * enabled and declare the required capability.
 */
public record CreateKnowledgeBaseCommand(
        TenantId tenantId,
        String slug,
        String name,
        String description,
        KnowledgeType knowledgeType,
        UUID embeddingProviderId,
        String embeddingModel,
        UUID rerankProviderId,
        String rerankModel,
        ChunkPolicy chunkPolicy,
        RetrievalConfig retrievalConfig,
        JsonNode metadata,
        UUID actorId
) {

    public CreateKnowledgeBaseCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
    }
}
