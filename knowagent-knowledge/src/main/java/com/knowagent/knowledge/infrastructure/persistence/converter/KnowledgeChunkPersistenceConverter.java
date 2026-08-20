package com.knowagent.knowledge.infrastructure.persistence.converter;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.chunk.KnowledgeChunk;
import com.knowagent.knowledge.infrastructure.persistence.entity.KnowledgeChunkPo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Maps {@link KnowledgeChunkPo} and {@link KnowledgeChunk}. Long offsets in the domain
 * are narrowed to the {@code integer} columns only when they fit (chunk sizes are bounded
 * by the parse budget); a stored row that violates a domain invariant is reported as an
 * internal persistence failure, never silently accepted. Chunk content is never part of
 * any exception message here.
 */
public final class KnowledgeChunkPersistenceConverter {

    private KnowledgeChunkPersistenceConverter() {
    }

    public static KnowledgeChunk toDomain(KnowledgeChunkPo source) {
        try {
            return new KnowledgeChunk(
                    required(source.getId(), "id"),
                    TenantId.of(required(source.getTenantId(), "tenant_id")),
                    required(source.getKnowledgeBaseId(), "knowledge_base_id"),
                    required(source.getFileId(), "file_id"),
                    source.getChunkIndex(),
                    required(source.getContent(), "content"),
                    required(source.getContentHash(), "content_hash"),
                    source.getTokenCount(),
                    longValue(source.getStartCharOffset()),
                    longValue(source.getEndCharOffset()),
                    longValue(source.getStartTokenOffset()),
                    longValue(source.getEndTokenOffset()),
                    source.getPageNumber(),
                    source.getSectionPath(),
                    source.getMetadata(),
                    required(source.getIndexStatus(), "index_status"),
                    source.getEmbeddingModelSpec(),
                    source.getErrorCode(),
                    source.getErrorMessage(),
                    requiredVersion(source.getVersion()),
                    instant(source.getCreatedAt()),
                    instant(source.getUpdatedAt()));
        } catch (RuntimeException exception) {
            throw invalidRow(exception);
        }
    }

    public static KnowledgeChunkPo toPersistence(KnowledgeChunk source) {
        try {
            KnowledgeChunkPo target = new KnowledgeChunkPo();
            target.setId(source.id());
            target.setTenantId(source.tenantId().value());
            target.setKnowledgeBaseId(source.knowledgeBaseId());
            target.setFileId(source.fileId());
            target.setChunkIndex(source.chunkIndex());
            target.setContent(source.content());
            target.setContentHash(source.contentHash());
            target.setTokenCount(source.tokenCount());
            target.setStartCharOffset(intValue(source.startCharOffset()));
            target.setEndCharOffset(intValue(source.endCharOffset()));
            target.setStartTokenOffset(intValue(source.startTokenOffset()));
            target.setEndTokenOffset(intValue(source.endTokenOffset()));
            target.setPageNumber(source.pageNumber());
            target.setSectionPath(source.sectionPath());
            target.setMetadata(source.metadata());
            target.setIndexStatus(source.indexStatus());
            target.setEmbeddingModelSpec(source.embeddingModelSpec());
            target.setErrorCode(source.errorCode());
            target.setErrorMessage(source.errorMessage());
            target.setVersion(source.version());
            target.setCreatedAt(offsetDateTime(source.createdAt()));
            target.setUpdatedAt(offsetDateTime(source.updatedAt()));
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

    private static Long longValue(Integer value) {
        return value == null ? null : value.longValue();
    }

    private static Integer intValue(Long value) {
        return value == null ? null : Math.toIntExact(value);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static BusinessException invalidRow(RuntimeException cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.INTERNAL_ERROR, "Invalid knowledge chunk persistence record");
        exception.initCause(cause);
        return exception;
    }
}
