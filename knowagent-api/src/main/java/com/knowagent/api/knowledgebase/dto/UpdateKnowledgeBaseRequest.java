package com.knowagent.api.knowledgebase.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Patch request: a {@code null} field means "keep the current value". Because {@code
 * null} means "keep", clearing a text field or unbinding a provider is not supported
 * in this milestone. {@code status} may only be {@code ACTIVE} or {@code DISABLED}
 * (use DELETE to remove a knowledge base); illegal transitions are rejected by the
 * service.
 */
public record UpdateKnowledgeBaseRequest(
        @Size(max = 99) String slug,
        @Size(max = 255) String name,
        @Size(max = 10000) String description,
        KnowledgeBaseStatus status,
        KnowledgeType knowledgeType,
        UUID embeddingProviderId,
        @Size(max = 255) String embeddingModel,
        UUID rerankProviderId,
        @Size(max = 255) String rerankModel,
        @Valid ChunkPolicyRequest chunkPolicy,
        @Valid RetrievalConfigRequest retrievalConfig,
        JsonNode metadata
) {
}
