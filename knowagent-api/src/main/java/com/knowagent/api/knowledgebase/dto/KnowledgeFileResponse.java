package com.knowagent.api.knowledgebase.dto;

import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.file.KnowledgeFileStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Public knowledge-file view. Carries only the source metadata and lifecycle status;
 * it deliberately omits the storage object key, the processing params (task/outbox ids),
 * error internals and audit user ids - none of those belong in a client response.
 */
public record KnowledgeFileResponse(
        UUID id,
        UUID knowledgeBaseId,
        String displayName,
        String originalFilename,
        String contentType,
        String fileExtension,
        String sha256,
        long fileSizeBytes,
        KnowledgeFileStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public static KnowledgeFileResponse from(KnowledgeFile file) {
        return new KnowledgeFileResponse(
                file.id(),
                file.knowledgeBaseId(),
                file.displayName(),
                file.originalFilename(),
                file.contentType(),
                file.fileExtension(),
                file.sha256(),
                file.fileSizeBytes(),
                file.status(),
                file.createdAt(),
                file.updatedAt());
    }
}
