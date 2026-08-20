package com.knowagent.api.knowledgebase.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.knowledge.knowledgebase.KnowledgeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Create request. Provider/model fields must be supplied together (or both omitted);
 * the service validates that the referenced providers belong to the tenant, are
 * enabled and declare the required capability. {@code chunkPolicy} and {@code
 * retrievalConfig} are optional and default in the service when omitted.
 */
public record CreateKnowledgeBaseRequest(
        @NotBlank @Size(max = 99) String slug,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 10000) String description,
        KnowledgeType knowledgeType,
        UUID embeddingProviderId,
        @Size(max = 255) String embeddingModel,
        UUID rerankProviderId,
        @Size(max = 255) String rerankModel,
        @Valid ChunkPolicyRequest chunkPolicy,
        @Valid RetrievalConfigRequest retrievalConfig,
        JsonNode metadata
) {

    public CreateKnowledgeBaseRequest {
        knowledgeType = knowledgeType == null ? KnowledgeType.LOCAL : knowledgeType;
        metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata;
    }
}
