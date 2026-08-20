package com.knowagent.api.knowledgebase.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeType;

import java.time.Instant;
import java.util.UUID;

/**
 * Public knowledge-base view. Carries only the typed configuration and metadata - never
 * the persistence object, provider secrets or internal header material. {@code version}
 * and the audit user ids are intentionally omitted.
 */
public record KnowledgeBaseResponse(
        UUID id,
        String slug,
        String name,
        String description,
        KnowledgeType knowledgeType,
        KnowledgeBaseStatus status,
        UUID embeddingProviderId,
        String embeddingModel,
        UUID rerankProviderId,
        String rerankModel,
        ChunkPolicyResponse chunkPolicy,
        RetrievalConfigResponse retrievalConfig,
        JsonNode metadata,
        Instant createdAt,
        Instant updatedAt
) {

    public static KnowledgeBaseResponse from(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseResponse(
                knowledgeBase.id(),
                knowledgeBase.slug(),
                knowledgeBase.name(),
                knowledgeBase.description(),
                knowledgeBase.knowledgeType(),
                knowledgeBase.status(),
                knowledgeBase.embeddingProviderId(),
                knowledgeBase.embeddingModel(),
                knowledgeBase.rerankProviderId(),
                knowledgeBase.rerankModel(),
                ChunkPolicyResponse.from(knowledgeBase.chunkPolicy()),
                RetrievalConfigResponse.from(knowledgeBase.retrievalConfig()),
                knowledgeBase.metadata(),
                knowledgeBase.createdAt(),
                knowledgeBase.updatedAt());
    }
}
