package com.knowagent.knowledge.application.service;

import com.knowagent.common.tenant.TenantId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application command for semantic retrieval. The tenant id is supplied by a
 * trusted caller (the authenticated principal), never by an HTTP request field.
 * Query text is intentionally excluded from {@link #toString()}.
 */
public record KnowledgeRetrievalCommand(
        TenantId tenantId,
        UUID knowledgeBaseId,
        String query,
        Integer topK,
        Double scoreThreshold,
        List<UUID> fileIds) {

    public KnowledgeRetrievalCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        fileIds = fileIds == null ? null : List.copyOf(fileIds);
    }

    @Override
    public String toString() {
        return "KnowledgeRetrievalCommand[tenantId=" + tenantId
                + ", knowledgeBaseId=" + knowledgeBaseId
                + ", topK=" + topK
                + ", scoreThreshold=" + scoreThreshold
                + ", fileCount=" + (fileIds == null ? 0 : fileIds.size()) + "]";
    }
}
