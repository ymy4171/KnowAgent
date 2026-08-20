package com.knowagent.knowledge.application.service;

import com.knowagent.common.tenant.TenantId;

import java.util.Objects;
import java.util.UUID;

/**
 * Input to {@link KnowledgeFileSubmissionService#submitUpload}: everything needed to
 * persist a knowledge file whose source object is already in object storage, together
 * with its ingest task and transactional outbox event. {@code objectKey} is the
 * server-built storage address and is deliberately absent from any {@code toString}.
 */
public record PersistUploadedFileCommand(
        UUID fileId,
        TenantId tenantId,
        UUID knowledgeBaseId,
        String uploadIdempotencyKey,
        String displayName,
        String originalFilename,
        String objectKey,
        String contentType,
        String fileExtension,
        String sha256,
        long fileSizeBytes,
        UUID actorId
) {

    public PersistUploadedFileCommand {
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(originalFilename, "originalFilename must not be null");
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        if (fileSizeBytes < 0) {
            throw new IllegalArgumentException("fileSizeBytes must not be negative");
        }
    }

    @Override
    public String toString() {
        return "PersistUploadedFileCommand[fileId=" + fileId + ", tenantId=" + tenantId
                + ", knowledgeBaseId=" + knowledgeBaseId + ", uploadIdempotencyKey=" + uploadIdempotencyKey
                + ", displayName=" + displayName + ", contentType=" + contentType
                + ", fileSizeBytes=" + fileSizeBytes + ", actorId=" + actorId + "]";
    }
}
