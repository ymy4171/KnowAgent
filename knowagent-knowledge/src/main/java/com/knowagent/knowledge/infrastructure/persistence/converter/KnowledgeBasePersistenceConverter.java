package com.knowagent.knowledge.infrastructure.persistence.converter;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.infrastructure.persistence.entity.KnowledgeBasePo;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Maps {@link KnowledgeBasePo} and {@link KnowledgeBase}. Provider ids stay opaque
 * UUIDs; nothing here touches provider configuration or secrets. A row that violates a
 * domain invariant (for example a half-configured provider pair or an invalid stored
 * JSONB policy) is reported as an internal persistence failure, not silently accepted.
 */
public final class KnowledgeBasePersistenceConverter {

    private KnowledgeBasePersistenceConverter() {
    }

    public static KnowledgeBase toDomain(KnowledgeBasePo source) {
        try {
            return new KnowledgeBase(
                    source.getId(),
                    TenantId.of(source.getTenantId()),
                    source.getSlug(),
                    source.getName(),
                    source.getDescription(),
                    requiredEnum(source.getKnowledgeType(), "knowledge_type"),
                    requiredEnum(source.getStatus(), "status"),
                    source.getEmbeddingProviderId(),
                    source.getEmbeddingModel(),
                    source.getRerankProviderId(),
                    source.getRerankModel(),
                    required(source.getChunkPolicy(), "chunk_policy"),
                    required(source.getRetrievalConfig(), "retrieval_config"),
                    required(source.getMetadata(), "metadata"),
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

    public static KnowledgeBasePo toPersistence(KnowledgeBase source) {
        try {
            KnowledgeBasePo target = new KnowledgeBasePo();
            target.setId(source.id());
            target.setTenantId(source.tenantId().value());
            target.setSlug(source.slug());
            target.setName(source.name());
            target.setDescription(source.description());
            target.setKnowledgeType(source.knowledgeType());
            target.setStatus(source.status());
            target.setEmbeddingProviderId(source.embeddingProviderId());
            target.setEmbeddingModel(source.embeddingModel());
            target.setRerankProviderId(source.rerankProviderId());
            target.setRerankModel(source.rerankModel());
            target.setChunkPolicy(source.chunkPolicy());
            target.setRetrievalConfig(source.retrievalConfig());
            target.setMetadata(source.metadata());
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

    private static <E extends Enum<E>> E requiredEnum(E value, String field) {
        return required(value, field);
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
                ErrorCode.INTERNAL_ERROR, "Invalid knowledge base persistence record");
        exception.initCause(cause);
        return exception;
    }
}
