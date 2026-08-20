package com.knowagent.knowledge.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.tenant.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A tenant's uploaded knowledge file: source metadata plus ingestion lifecycle state.
 *
 * <p>The object key is opaque to the API (never exposed in responses or
 * {@code toString}); it is only a server-side address used by the content endpoint and
 * the future worker. {@code processingParams} may carry internal ids such as the
 * ingest task id for idempotent replay and is likewise never part of a response.
 * Status transitions go through {@link #transitionTo} so the centralized
 * {@link KnowledgeFileStatus} state machine is the single source of truth.
 */
public record KnowledgeFile(
        UUID id,
        TenantId tenantId,
        UUID knowledgeBaseId,
        UUID parentFileId,
        String uploadIdempotencyKey,
        String displayName,
        String originalFilename,
        String objectKey,
        String contentType,
        String fileExtension,
        String sha256,
        long fileSizeBytes,
        KnowledgeFileStatus status,
        int chunkCount,
        long tokenCount,
        JsonNode processingParams,
        JsonNode metadata,
        String errorCode,
        String errorMessage,
        boolean retryable,
        UUID createdBy,
        UUID updatedBy,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAX_DISPLAY_NAME_LENGTH = 512;

    public KnowledgeFile {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        if (isBlank(displayName)) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("displayName must contain at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        if (isBlank(originalFilename)) {
            throw new IllegalArgumentException("originalFilename must not be blank");
        }
        if (originalFilename.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("originalFilename must contain at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        if (isBlank(objectKey)) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
        if (isBlank(contentType)) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (!SHA256_HEX.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
        }
        if (fileSizeBytes < 0) {
            throw new IllegalArgumentException("fileSizeBytes must not be negative");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (chunkCount < 0 || tokenCount < 0) {
            throw new IllegalArgumentException("chunkCount and tokenCount must not be negative");
        }
        if (processingParams == null || !processingParams.isObject()) {
            throw new IllegalArgumentException("processingParams must be a JSON object");
        }
        if (metadata == null || !metadata.isObject()) {
            throw new IllegalArgumentException("metadata must be a JSON object");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        processingParams = processingParams.deepCopy();
        metadata = metadata.deepCopy();
    }

    /** Applies the centralized state machine and returns a copy in the new status. */
    public KnowledgeFile transitionTo(KnowledgeFileStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalArgumentException("illegal knowledge-file transition from " + status + " to " + target);
        }
        return new KnowledgeFile(id, tenantId, knowledgeBaseId, parentFileId, uploadIdempotencyKey,
                displayName, originalFilename, objectKey, contentType, fileExtension, sha256,
                fileSizeBytes, target, chunkCount, tokenCount, processingParams, metadata,
                errorCode, errorMessage, retryable, createdBy, updatedBy, version, createdAt,
                Instant.now(), deletedAt);
    }

    /**
     * Returns the post-update view of a persisted Worker transition. Unlike the
     * upload-only {@link #transitionTo(KnowledgeFileStatus)} helper, this method
     * bumps the optimistic-lock version and replaces the stable failure fields.
     */
    public KnowledgeFile persistedTransitionTo(KnowledgeFileStatus target,
                                               String nextErrorCode,
                                               String nextErrorMessage,
                                               boolean nextRetryable,
                                               Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalArgumentException("illegal knowledge-file transition from " + status + " to " + target);
        }
        return new KnowledgeFile(id, tenantId, knowledgeBaseId, parentFileId, uploadIdempotencyKey,
                displayName, originalFilename, objectKey, contentType, fileExtension, sha256,
                fileSizeBytes, target, chunkCount, tokenCount, processingParams, metadata,
                nextErrorCode, nextErrorMessage, nextRetryable, createdBy, updatedBy,
                version + 1, createdAt, now, deletedAt);
    }

    public static JsonNode emptyProcessingParams() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static JsonNode emptyMetadata() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "KnowledgeFile[id=" + id + ", tenantId=" + tenantId + ", knowledgeBaseId=" + knowledgeBaseId
                + ", displayName=" + displayName + ", status=" + status + ", fileSizeBytes=" + fileSizeBytes
                + ", version=" + version + "]";
    }
}
