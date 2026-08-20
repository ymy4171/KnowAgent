package com.knowagent.api.task.dto;

import com.knowagent.observability.task.Task;
import com.knowagent.observability.task.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Task status as exposed to the API. Deliberately excludes {@code payload} and
 * {@code result}: the payload can reference storage keys and the result can carry
 * parsed content, neither of which belongs in a response. Only progress/status
 * fields are surfaced.
 */
public record TaskResponse(
        UUID id,
        String taskType,
        String aggregateType,
        String aggregateId,
        TaskStatus status,
        String stage,
        int progress,
        int attemptCount,
        int maxAttempts,
        String errorCode,
        String errorMessage,
        boolean retryable,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {

    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.id(),
                task.taskType(),
                task.aggregateType(),
                task.aggregateId(),
                task.status(),
                task.stage(),
                task.progress(),
                task.attemptCount(),
                task.maxAttempts(),
                task.errorCode(),
                task.errorMessage(),
                task.retryable(),
                task.createdAt(),
                task.startedAt(),
                task.completedAt());
    }
}
