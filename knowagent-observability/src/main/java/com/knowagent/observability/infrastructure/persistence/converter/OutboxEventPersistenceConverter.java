package com.knowagent.observability.infrastructure.persistence.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.infrastructure.persistence.entity.OutboxEventPo;
import com.knowagent.observability.outbox.OutboxEvent;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Maps {@link OutboxEventPo} and {@link OutboxEvent}. The domain allows {@code null}
 * headers for ergonomic construction; the {@code NOT NULL} column gets an empty
 * JSON object on the way down.
 */
public final class OutboxEventPersistenceConverter {

    private OutboxEventPersistenceConverter() {
    }

    public static OutboxEvent toDomain(OutboxEventPo source) {
        try {
            return new OutboxEvent(
                    source.getId(),
                    TenantId.of(source.getTenantId()),
                    source.getAggregateType(),
                    source.getAggregateId(),
                    source.getEventType(),
                    source.getPayload(),
                    source.getHeaders(),
                    source.getStatus(),
                    TaskPersistenceConverter.requiredInt(source.getRetryCount(), "retryCount"),
                    TaskPersistenceConverter.requiredInt(source.getMaxRetries(), "maxRetries"),
                    source.getNextRetryAt().toInstant(),
                    source.getLockedBy(),
                    source.getLockedUntil() == null ? null : source.getLockedUntil().toInstant(),
                    source.getLastError(),
                    source.getPublishedAt() == null ? null : source.getPublishedAt().toInstant(),
                    TaskPersistenceConverter.requiredLong(source.getVersion()),
                    source.getCreatedAt().toInstant());
        } catch (RuntimeException exception) {
            throw invalidRow(exception);
        }
    }

    public static OutboxEventPo toPersistence(OutboxEvent source) {
        try {
            OutboxEventPo target = new OutboxEventPo();
            target.setId(source.id());
            target.setTenantId(source.tenantId().value());
            target.setAggregateType(source.aggregateType());
            target.setAggregateId(source.aggregateId());
            target.setEventType(source.eventType());
            target.setPayload(source.payload());
            target.setHeaders(objectOrEmpty(source.headers()));
            target.setStatus(source.status());
            target.setRetryCount(source.retryCount());
            target.setMaxRetries(source.maxRetries());
            target.setNextRetryAt(offsetDateTime(source.nextRetryAt()));
            target.setLockedBy(source.lockedBy());
            target.setLockedUntil(offsetDateTime(source.lockedUntil()));
            target.setLastError(source.lastError());
            target.setPublishedAt(offsetDateTime(source.publishedAt()));
            target.setVersion(source.version());
            target.setCreatedAt(offsetDateTime(source.createdAt()));
            return target;
        } catch (RuntimeException exception) {
            throw invalidRow(exception);
        }
    }

    private static JsonNode objectOrEmpty(JsonNode node) {
        return node == null ? JsonNodeFactory.instance.objectNode() : node;
    }

    private static OffsetDateTime offsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static BusinessException invalidRow(RuntimeException cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.INTERNAL_ERROR, "Invalid outbox event persistence record");
        exception.initCause(cause);
        return exception;
    }
}
