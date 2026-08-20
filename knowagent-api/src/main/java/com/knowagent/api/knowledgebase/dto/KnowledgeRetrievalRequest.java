package com.knowagent.api.knowledgebase.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Retrieval input. Tenant identity is intentionally absent and cannot be overridden. */
public record KnowledgeRetrievalRequest(
        @NotBlank @Size(max = 10_000) String query,
        @Min(1) @Max(100) Integer topK,
        @DecimalMin("0.0") @DecimalMax("1.0") Double scoreThreshold,
        @Size(max = 100) List<@NotNull UUID> fileIds) {

    public KnowledgeRetrievalRequest {
        fileIds = fileIds == null ? null : List.copyOf(fileIds);
    }

    @Override
    public String toString() {
        return "KnowledgeRetrievalRequest[topK=" + topK + ", scoreThreshold=" + scoreThreshold
                + ", fileCount=" + (fileIds == null ? 0 : fileIds.size()) + "]";
    }
}
