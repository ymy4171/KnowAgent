package com.knowagent.knowledge.infrastructure.persistence.converter;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.infrastructure.persistence.entity.KnowledgeFilePo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Maps {@link KnowledgeFilePo} and {@link KnowledgeFile}. {@code objectKey} is copied
 * through as an opaque storage address and is never part of any response or log. A row
 * that violates a domain invariant (a bad stored SHA-256 or JSONB payload) is reported
 * as an internal persistence failure, not silently accepted.
 */
public final class KnowledgeFilePersistenceConverter {

    private KnowledgeFilePersistenceConverter() {
    }

    public static KnowledgeFile toDomain(KnowledgeFilePo source) {
        try {
            return new KnowledgeFile(
                    required(source.getId(), "id"),
                    TenantId.of(required(source.getTenantId(), "tenant_id")),
                    required(source.getKnowledgeBaseId(), "knowledge_base_id"),
                    source.getParentFileId(),
                    source.getUploadIdempotencyKey(),
                    required(source.getDisplayName(), "display_name"),
                    required(source.getOriginalFilename(), "original_filename"),
                    required(source.getObjectKey(), "object_key"),
                    required(source.getContentType(), "content_type"),
                    source.getFileExtension(),
                    required(source.getSha256(), "sha256"),
                    source.getFileSizeBytes(),
                    required(source.getStatus(), "status"),
                    source.getChunkCount(),
                    source.getTokenCount(),
                    required(source.getProcessingParams(), "processing_params"),
                    required(source.getMetadata(), "metadata"),
                    source.getErrorCode(),
                    source.getErrorMessage(),
                    source.isRetryable(),
                    source.getCreatedBy(),
                    source.getUpdatedBy(),
                    requiredVersion(source.getVersion()),
                    instant(source.getCreatedAt()),
                    instant(source.getUpdatedAt()),
                    instant(source.getDeletedAt()));
        } catch (RuntimeException exception) {
            throw invalidRow(exception);
        }
    }

    public static KnowledgeFilePo toPersistence(KnowledgeFile source) {
        try {
            KnowledgeFilePo target = new KnowledgeFilePo();
            target.setId(source.id());
            target.setTenantId(source.tenantId().value());
            target.setKnowledgeBaseId(source.knowledgeBaseId());
            target.setParentFileId(source.parentFileId());
            target.setUploadIdempotencyKey(source.uploadIdempotencyKey());
            target.setDisplayName(source.displayName());
            target.setOriginalFilename(source.originalFilename());
            target.setObjectKey(source.objectKey());
            target.setContentType(source.contentType());
            target.setFileExtension(source.fileExtension());
            target.setSha256(source.sha256());
            target.setFileSizeBytes(source.fileSizeBytes());
            target.setStatus(source.status());
            target.setChunkCount(source.chunkCount());
            target.setTokenCount(source.tokenCount());
            target.setProcessingParams(source.processingParams());
            target.setMetadata(source.metadata());
            target.setErrorCode(source.errorCode());
            target.setErrorMessage(source.errorMessage());
            target.setRetryable(source.retryable());
            target.setCreatedBy(source.createdBy());
            target.setUpdatedBy(source.updatedBy());
            target.setVersion(source.version());
            target.setCreatedAt(offsetDateTime(source.createdAt()));
            target.setUpdatedAt(offsetDateTime(source.updatedAt()));
            target.setDeletedAt(offsetDateTime(source.deletedAt()));
            return target;
        } catch (RuntimeException exception) {
            throw invalidRow(exception);
        }
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    private static long requiredVersion(Long value) {
        if (value == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        return value;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static BusinessException invalidRow(RuntimeException cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.INTERNAL_ERROR, "Invalid knowledge file persistence record");
        exception.initCause(cause);
        return exception;
    }
}
