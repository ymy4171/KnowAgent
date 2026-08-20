package com.knowagent.knowledge.chunk;

import com.knowagent.common.tenant.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A persisted text chunk. The UUID is generated in Java before insert and doubles as the
 * vector entity id in Milvus later, so it must never be regenerated on retry. Char and
 * token offsets are nullable as a paired set, mirroring the {@code knowledge_chunks}
 * CHECK constraints. The content itself is excluded from {@code toString()} so document
 * text never leaks into logs or exceptions.
 */
public record KnowledgeChunk(
        UUID id,
        TenantId tenantId,
        UUID knowledgeBaseId,
        UUID fileId,
        int chunkIndex,
        String content,
        String contentHash,
        int tokenCount,
        Long startCharOffset,
        Long endCharOffset,
        Long startTokenOffset,
        Long endTokenOffset,
        Integer pageNumber,
        List<String> sectionPath,
        Map<String, String> metadata,
        ChunkIndexStatus indexStatus,
        String embeddingModelSpec,
        String errorCode,
        String errorMessage,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public KnowledgeChunk {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }
        Objects.requireNonNull(content, "content must not be null");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        if (!SHA256_HEX.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash must be 64 lowercase hex characters");
        }
        if (tokenCount < 0) {
            throw new IllegalArgumentException("tokenCount must not be negative");
        }
        if ((startCharOffset == null) != (endCharOffset == null)
                || (startCharOffset != null
                        && (startCharOffset < 0 || endCharOffset < startCharOffset))) {
            throw new IllegalArgumentException("char offsets must be a paired, ordered 0 <= start <= end range");
        }
        if ((startTokenOffset == null) != (endTokenOffset == null)
                || (startTokenOffset != null
                        && (startTokenOffset < 0 || endTokenOffset < startTokenOffset))) {
            throw new IllegalArgumentException("token offsets must be a paired, ordered 0 <= start <= end range");
        }
        if (pageNumber != null && pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be 1-based when present");
        }
        Objects.requireNonNull(indexStatus, "indexStatus must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override
    public String toString() {
        return "KnowledgeChunk[id=" + id + ", tenantId=" + tenantId + ", knowledgeBaseId=" + knowledgeBaseId
                + ", fileId=" + fileId + ", chunkIndex=" + chunkIndex + ", tokenCount=" + tokenCount
                + ", indexStatus=" + indexStatus + ", version=" + version + "]";
    }
}
