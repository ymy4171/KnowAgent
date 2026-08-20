package com.knowagent.observability.infrastructure.persistence.converter;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.infrastructure.persistence.entity.TaskPo;
import com.knowagent.observability.task.Task;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Maps {@link TaskPo} and {@link Task}. {@code payload} is always an object (the
 * database CHECK enforces it); a malformed stored row surfaces as an internal
 * error, never as a silent default.
 */
public final class TaskPersistenceConverter {

    private TaskPersistenceConverter() {
    }

    public static Task toDomain(TaskPo source) {
        try {
            return new Task(
                    source.getId(),
                    TenantId.of(source.getTenantId()),
                    source.getTaskType(),
                    source.getAggregateType(),
                    source.getAggregateId(),
                    source.getIdempotencyKey(),
                    source.getStatus(),
                    source.getStage(),
                    requiredInt(source.getProgress(), "progress"),
                    source.getPayload(),
                    source.getResult(),
                    requiredInt(source.getAttemptCount(), "attemptCount"),
                    requiredInt(source.getMaxAttempts(), "maxAttempts"),
                    instant(source.getNextRetryAt()),
                    source.getLockedBy(),
                    instant(source.getLockedUntil()),
                    instant(source.getCancelRequestedAt()),
                    source.getErrorCode(),
                    source.getErrorMessage(),
                    Boolean.TRUE.equals(source.getRetryable()),
                    instant(source.getStartedAt()),
                    instant(source.getCompletedAt()),
                    requiredLong(source.getVersion()),
                    instant(source.getCreatedAt()),
                    instant(source.getUpdatedAt()));
        } catch (RuntimeException exception) {
            throw invalidRow(exception);
        }
    }

    public static TaskPo toPersistence(Task source) {
        try {
            TaskPo target = new TaskPo();
            target.setId(source.id());
            target.setTenantId(source.tenantId().value());
            target.setTaskType(source.taskType());
            target.setAggregateType(source.aggregateType());
            target.setAggregateId(source.aggregateId());
            target.setIdempotencyKey(source.idempotencyKey());
            target.setStatus(source.status());
            target.setStage(source.stage());
            target.setProgress(source.progress());
            target.setPayload(source.payload());
            target.setResult(source.result());
            target.setAttemptCount(source.attemptCount());
            target.setMaxAttempts(source.maxAttempts());
            target.setNextRetryAt(offsetDateTime(source.nextRetryAt()));
            target.setLockedBy(source.lockedBy());
            target.setLockedUntil(offsetDateTime(source.lockedUntil()));
            target.setCancelRequestedAt(offsetDateTime(source.cancelRequestedAt()));
            target.setErrorCode(source.errorCode());
            target.setErrorMessage(source.errorMessage());
            target.setRetryable(source.retryable());
            target.setStartedAt(offsetDateTime(source.startedAt()));
            target.setCompletedAt(offsetDateTime(source.completedAt()));
            target.setVersion(source.version());
            target.setCreatedAt(offsetDateTime(source.createdAt()));
            target.setUpdatedAt(offsetDateTime(source.updatedAt()));
            return target;
        } catch (RuntimeException exception) {
            throw invalidRow(exception);
        }
    }

    static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime offsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    static int requiredInt(Integer value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    static long requiredLong(Long value) {
        if (value == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        return value;
    }

    static BusinessException invalidRow(RuntimeException cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.INTERNAL_ERROR, "Invalid task persistence record");
        exception.initCause(cause);
        return exception;
    }
}
