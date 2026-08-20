package com.knowagent.observability.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.application.port.out.OutboxEventStore;
import com.knowagent.observability.application.port.out.TaskStore;
import com.knowagent.observability.application.port.out.TaskTransition;
import com.knowagent.observability.outbox.OutboxEvent;
import com.knowagent.observability.outbox.OutboxStatus;
import com.knowagent.observability.task.Task;
import com.knowagent.observability.task.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TaskSubmissionServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());

    private final FakeTaskStore tasks = new FakeTaskStore();
    private final FakeOutboxEventStore outboxEvents = new FakeOutboxEventStore();
    private final TaskSubmissionService service = new TaskSubmissionService(tasks, outboxEvents);

    @Test
    void submitWritesTaskAndOutboxEventAtomically() {
        TaskSubmissionResult result = service.submit(validCommand());

        assertThat(tasks.saved).hasSize(1);
        Task task = tasks.saved.get(0);
        assertThat(task.id()).isEqualTo(result.taskId());
        assertThat(task.tenantId()).isEqualTo(TENANT);
        assertThat(task.taskType()).isEqualTo("ingest");
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.attemptCount()).isZero();
        assertThat(task.maxAttempts()).isEqualTo(3);
        assertThat(task.idempotencyKey()).isEqualTo("idem-1");
        assertThat(task.createdAt()).isEqualTo(result.createdAt());
        assertThat(task.updatedAt()).isEqualTo(result.createdAt());

        assertThat(outboxEvents.appended).hasSize(1);
        OutboxEvent event = outboxEvents.appended.get(0);
        assertThat(event.id()).isEqualTo(result.outboxEventId());
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.eventType()).isEqualTo("kb.created");
        assertThat(event.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.retryCount()).isZero();
        assertThat(event.maxRetries()).isEqualTo(3);
    }

    @Test
    void generatedIdsAreUniquePerSubmission() {
        TaskSubmissionResult first = service.submit(validCommand());
        TaskSubmissionResult second = service.submit(validCommand());

        assertThat(first.taskId()).isNotEqualTo(second.taskId());
        assertThat(first.outboxEventId()).isNotEqualTo(second.outboxEventId());
    }

    @Test
    void rejectsBlankOrOversizedTaskType() {
        SubmitTaskCommand blank = withTaskType(validCommand(), " ");
        SubmitTaskCommand oversized = withTaskType(validCommand(), "x".repeat(65));
        assertValidationError(blank);
        assertValidationError(oversized);
    }

    @Test
    void rejectsNonObjectTaskPayload() {
        assertValidationError(withTaskPayload(validCommand(), OBJECT_MAPPER.createArrayNode()));
    }

    @Test
    void rejectsMaxAttemptsOutsideRange() {
        assertValidationError(withMaxAttempts(validCommand(), 0));
        assertValidationError(withMaxAttempts(validCommand(), TaskSubmissionService.MAX_ATTEMPTS + 1));
    }

    @Test
    void rejectsBlankEventType() {
        assertValidationError(withEventType(validCommand(), null));
        assertValidationError(withEventType(validCommand(), ""));
    }

    @Test
    void rejectsNonObjectEventPayloadOrHeaders() {
        assertValidationError(withEventPayload(validCommand(), null));
        assertValidationError(withEventPayload(validCommand(), OBJECT_MAPPER.getNodeFactory().textNode("x")));
        assertValidationError(withEventHeaders(validCommand(), OBJECT_MAPPER.createArrayNode()));
    }

    @Test
    void rejectsEventMaxRetriesOutsideRange() {
        assertValidationError(withEventMaxRetries(validCommand(), 0));
        assertValidationError(withEventMaxRetries(validCommand(), TaskSubmissionService.MAX_EVENT_RETRIES + 1));
    }

    @Test
    void failedValidationWritesNothing() {
        assertValidationError(withTaskType(validCommand(), "x".repeat(65)));

        assertThat(tasks.saved).isEmpty();
        assertThat(outboxEvents.appended).isEmpty();
    }

    private void assertValidationError(SubmitTaskCommand command) {
        BusinessException ex = catchThrowableOfType(() -> service.submit(command), BusinessException.class);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    private static SubmitTaskCommand validCommand() {
        return new SubmitTaskCommand(
                TENANT, "ingest", "knowledge_base", "kb-1", "idem-1",
                object(), 3, "kb.created", object(), object(), 3);
    }

    private static SubmitTaskCommand withTaskType(SubmitTaskCommand c, String taskType) {
        return new SubmitTaskCommand(c.tenantId(), taskType, c.aggregateType(), c.aggregateId(),
                c.idempotencyKey(), c.taskPayload(), c.maxAttempts(), c.eventType(),
                c.eventPayload(), c.eventHeaders(), c.eventMaxRetries());
    }

    private static SubmitTaskCommand withTaskPayload(SubmitTaskCommand c, JsonNode payload) {
        return new SubmitTaskCommand(c.tenantId(), c.taskType(), c.aggregateType(), c.aggregateId(),
                c.idempotencyKey(), payload, c.maxAttempts(), c.eventType(),
                c.eventPayload(), c.eventHeaders(), c.eventMaxRetries());
    }

    private static SubmitTaskCommand withMaxAttempts(SubmitTaskCommand c, int maxAttempts) {
        return new SubmitTaskCommand(c.tenantId(), c.taskType(), c.aggregateType(), c.aggregateId(),
                c.idempotencyKey(), c.taskPayload(), maxAttempts, c.eventType(),
                c.eventPayload(), c.eventHeaders(), c.eventMaxRetries());
    }

    private static SubmitTaskCommand withEventType(SubmitTaskCommand c, String eventType) {
        return new SubmitTaskCommand(c.tenantId(), c.taskType(), c.aggregateType(), c.aggregateId(),
                c.idempotencyKey(), c.taskPayload(), c.maxAttempts(), eventType,
                c.eventPayload(), c.eventHeaders(), c.eventMaxRetries());
    }

    private static SubmitTaskCommand withEventPayload(SubmitTaskCommand c, JsonNode payload) {
        return new SubmitTaskCommand(c.tenantId(), c.taskType(), c.aggregateType(), c.aggregateId(),
                c.idempotencyKey(), c.taskPayload(), c.maxAttempts(), c.eventType(),
                payload, c.eventHeaders(), c.eventMaxRetries());
    }

    private static SubmitTaskCommand withEventHeaders(SubmitTaskCommand c, JsonNode headers) {
        return new SubmitTaskCommand(c.tenantId(), c.taskType(), c.aggregateType(), c.aggregateId(),
                c.idempotencyKey(), c.taskPayload(), c.maxAttempts(), c.eventType(),
                c.eventPayload(), headers, c.eventMaxRetries());
    }

    private static SubmitTaskCommand withEventMaxRetries(SubmitTaskCommand c, int maxRetries) {
        return new SubmitTaskCommand(c.tenantId(), c.taskType(), c.aggregateType(), c.aggregateId(),
                c.idempotencyKey(), c.taskPayload(), c.maxAttempts(), c.eventType(),
                c.eventPayload(), c.eventHeaders(), maxRetries);
    }

    private static JsonNode object() {
        return OBJECT_MAPPER.createObjectNode().put("source", "unit-test");
    }

    private static final class FakeTaskStore implements TaskStore {
        final List<Task> saved = new ArrayList<>();
        Optional<Task> found = Optional.empty();

        @Override
        public void save(Task task) {
            saved.add(task);
        }

        @Override
        public Optional<Task> findById(TenantId tenantId, UUID taskId) {
            return found;
        }

        @Override
        public Optional<Task> claim(TenantId tenantId, UUID taskId, String workerId, Instant now, Duration lease) {
            return Optional.empty();
        }

        @Override
        public Optional<Task> updateProgress(Task current, String stage, int progress, Instant now, Duration lease) {
            return Optional.empty();
        }

        @Override
        public int transition(Task current, TaskTransition transition) {
            return 0;
        }
    }

    private static final class FakeOutboxEventStore implements OutboxEventStore {
        final List<OutboxEvent> appended = new ArrayList<>();

        @Override
        public void append(OutboxEvent event) {
            appended.add(event);
        }

        @Override
        public Optional<OutboxEvent> findById(TenantId tenantId, UUID eventId) {
            return Optional.empty();
        }

        @Override
        public List<OutboxEvent> claimReady(int limit, Instant now, String workerId, Duration lease) {
            return List.of();
        }

        @Override
        public int markPublished(OutboxEvent event) {
            return 0;
        }

        @Override
        public int markFailed(OutboxEvent current, OutboxEvent target) {
            return 0;
        }
    }
}
