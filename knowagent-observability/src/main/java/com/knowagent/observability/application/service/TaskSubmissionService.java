package com.knowagent.observability.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.observability.application.port.out.OutboxEventStore;
import com.knowagent.observability.application.port.out.TaskStore;
import com.knowagent.observability.outbox.OutboxEvent;
import com.knowagent.observability.outbox.OutboxStatus;
import com.knowagent.observability.task.Task;
import com.knowagent.observability.task.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes a task and its outbox event together with the caller's business record in
 * a single Spring transaction ({@code REQUIRED} joins the caller's transaction).
 *
 * <p>Validation happens here, before any insert, so a malformed submission fails
 * fast and rolls the whole transaction back together with the business record -
 * never a half-written task/event pair. {@code tenant_id} comes only from the
 * caller (which derives it from the authenticated principal), never from a client.
 */
@Service
public class TaskSubmissionService implements TaskSubmission {

    public static final int MAX_ATTEMPTS = 100;
    public static final int MAX_EVENT_RETRIES = 100;

    private final TaskStore tasks;
    private final OutboxEventStore outboxEvents;

    public TaskSubmissionService(TaskStore tasks, OutboxEventStore outboxEvents) {
        this.tasks = Objects.requireNonNull(tasks, "tasks must not be null");
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents must not be null");
    }

    @Override
    @Transactional
    public TaskSubmissionResult submit(SubmitTaskCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validate(command);

        Instant now = Instant.now();
        UUID taskId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        Task task = new Task(
                taskId, command.tenantId(), command.taskType(), command.aggregateType(),
                command.aggregateId(), command.idempotencyKey(), TaskStatus.PENDING, null, 0,
                command.taskPayload(), null, 0, command.maxAttempts(), null, null, null, null,
                null, null, false, null, null, 0, now, now);
        tasks.save(task);

        OutboxEvent event = new OutboxEvent(
                eventId, command.tenantId(), command.aggregateType(), command.aggregateId(),
                command.eventType(), command.eventPayload(), command.eventHeaders(),
                OutboxStatus.PENDING, 0, command.eventMaxRetries(), now, null, null, null, null,
                0, now);
        outboxEvents.append(event);

        return new TaskSubmissionResult(taskId, eventId, now);
    }

    private static void validate(SubmitTaskCommand command) {
        if (command.taskType() == null || command.taskType().isBlank()
                || command.taskType().length() > 64) {
            throw invalid("taskType must be a non-blank string of at most 64 characters");
        }
        JsonNode payload = command.taskPayload();
        if (payload == null || !payload.isObject()) {
            throw invalid("taskPayload must be a JSON object");
        }
        if (command.maxAttempts() < 1 || command.maxAttempts() > MAX_ATTEMPTS) {
            throw invalid("maxAttempts must be in [1, " + MAX_ATTEMPTS + "]");
        }
        if (command.eventType() == null || command.eventType().isBlank()) {
            throw invalid("eventType must be a non-blank string");
        }
        JsonNode eventPayload = command.eventPayload();
        if (eventPayload == null || !eventPayload.isObject()) {
            throw invalid("eventPayload must be a JSON object");
        }
        if (command.eventHeaders() != null && !command.eventHeaders().isObject()) {
            throw invalid("eventHeaders must be a JSON object");
        }
        if (command.eventMaxRetries() < 1 || command.eventMaxRetries() > MAX_EVENT_RETRIES) {
            throw invalid("eventMaxRetries must be in [1, " + MAX_EVENT_RETRIES + "]");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
