package com.knowagent.observability.infrastructure.persistence.converter;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.infrastructure.persistence.entity.InboxEventPo;
import com.knowagent.observability.inbox.InboxEvent;

import java.time.ZoneOffset;

/**
 * Maps {@link InboxEventPo} and {@link InboxEvent}.
 */
public final class InboxEventPersistenceConverter {

    private InboxEventPersistenceConverter() {
    }

    public static InboxEvent toDomain(InboxEventPo source) {
        try {
            return new InboxEvent(
                    source.getId(),
                    TenantId.of(source.getTenantId()),
                    source.getConsumerName(),
                    source.getEventId(),
                    source.getEventType(),
                    source.getPayloadHash(),
                    source.getProcessedAt().toInstant());
        } catch (RuntimeException exception) {
            throw invalidRow(exception);
        }
    }

    public static InboxEventPo toPersistence(InboxEvent source) {
        try {
            InboxEventPo target = new InboxEventPo();
            target.setId(source.id());
            target.setTenantId(source.tenantId().value());
            target.setConsumerName(source.consumerName());
            target.setEventId(source.eventId());
            target.setEventType(source.eventType());
            target.setPayloadHash(source.payloadHash());
            target.setProcessedAt(source.processedAt().atOffset(ZoneOffset.UTC));
            return target;
        } catch (RuntimeException exception) {
            throw invalidRow(exception);
        }
    }

    private static BusinessException invalidRow(RuntimeException cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.INTERNAL_ERROR, "Invalid inbox event persistence record");
        exception.initCause(cause);
        return exception;
    }
}
