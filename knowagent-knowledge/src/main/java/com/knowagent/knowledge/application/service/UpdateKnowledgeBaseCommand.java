package com.knowagent.knowledge.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.chunk.ChunkPolicy;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeType;
import com.knowagent.knowledge.knowledgebase.RetrievalConfig;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable patch command: a {@code null} field means "keep the current value".
 * Because {@code null} means "keep", clearing a text field or unbinding a provider is
 * not supported in this milestone (the model-provider update uses the same contract).
 * Provider/model fields submitted together are validated; submitting only one side of
 * a pair resolves the other side from the current row and rejects a half-configured
 * result.
 */
public record UpdateKnowledgeBaseCommand(
        TenantId tenantId,
        UUID knowledgeBaseId,
        String slug,
        String name,
        String description,
        KnowledgeBaseStatus status,
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

    public UpdateKnowledgeBaseCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
    }
}
