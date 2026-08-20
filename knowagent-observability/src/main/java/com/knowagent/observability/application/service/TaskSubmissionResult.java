package com.knowagent.observability.application.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of {@link TaskSubmission#submit}: the ids of the task and its outbox
 * event, written atomically in the same transaction as the caller's business record.
 */
public record TaskSubmissionResult(UUID taskId, UUID outboxEventId, Instant createdAt) {

    public TaskSubmissionResult {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(outboxEventId, "outboxEventId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
