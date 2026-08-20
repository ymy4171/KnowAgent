package com.knowagent.knowledge.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.chunk.ChunkIndexStatus;
import com.knowagent.knowledge.file.KnowledgeFileStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL projection used to verify a vector candidate. It deliberately carries
 * the authoritative tenant, knowledge-base, file and lifecycle facts so the
 * application can fail closed even if a persistence adapter is implemented
 * incorrectly. Content is excluded from {@link #toString()}.
 */
public record RetrievalChunkRecord(
        UUID chunkId,
        TenantId tenantId,
        UUID knowledgeBaseId,
        UUID fileId,
        String displayName,
        String content,
        Integer pageNumber,
        List<String> sectionPath,
        ChunkIndexStatus indexStatus,
        KnowledgeFileStatus fileStatus,
        Instant fileDeletedAt) {

    public RetrievalChunkRecord {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(content, "content must not be null");
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        Objects.requireNonNull(indexStatus, "indexStatus must not be null");
        Objects.requireNonNull(fileStatus, "fileStatus must not be null");
    }

    @Override
    public String toString() {
        return "RetrievalChunkRecord[chunkId=" + chunkId + ", tenantId=" + tenantId
                + ", knowledgeBaseId=" + knowledgeBaseId + ", fileId=" + fileId
                + ", indexStatus=" + indexStatus + ", fileStatus=" + fileStatus + "]";
    }
}
