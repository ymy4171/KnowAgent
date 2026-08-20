package com.knowagent.api.knowledgebase.dto;

import com.knowagent.knowledge.application.service.UploadFileResult;
import com.knowagent.knowledge.file.KnowledgeFileStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The 202 answer to a knowledge-file upload: the file is accepted and {@code QUEUED}
 * for ingestion, never parsed or embedded synchronously. {@code taskId} identifies the
 * ingest task when one exists. {@code replayed} is true when the caller's
 * {@code Idempotency-Key} matched an existing upload with identical content, in which
 * case {@code fileId} refers to the original file and no new task was created.
 */
public record UploadFileResponse(
        UUID fileId,
        UUID taskId,
        KnowledgeFileStatus status,
        boolean replayed,
        String sha256,
        long fileSizeBytes,
        Instant createdAt
) {

    public static UploadFileResponse from(UploadFileResult result) {
        return new UploadFileResponse(
                result.file().id(),
                result.taskId(),
                result.file().status(),
                result.replayed(),
                result.file().sha256(),
                result.file().fileSizeBytes(),
                result.file().createdAt());
    }
}
