package com.knowagent.observability.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.common.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

    @Test
    void acceptsAValidPendingTask() {
        Task task = validTask();
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.attemptCount()).isZero();
        assertThat(task.version()).isZero();
        assertThat(task.progress()).isZero();
    }

    @Test
    void rejectsBlankTaskType() {
        assertThatThrownBy(() -> new Task(UUID.randomUUID(), TENANT, " ", "knowledge_base", "kb-1",
                null, TaskStatus.PENDING, null, 0, object(), null, 0, 3, null, null, null, null, null, null,
                false, null, null, 0, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsProgressOutsideRange() {
        assertThatThrownBy(() -> new Task(UUID.randomUUID(), TENANT, "ingest", "knowledge_base", "kb-1",
                null, TaskStatus.PENDING, null, 101, object(), null, 0, 3, null, null, null, null, null, null,
                false, null, null, 0, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonObjectPayloadAndResult() {
        assertThatThrownBy(() -> new Task(UUID.randomUUID(), TENANT, "ingest", "knowledge_base", "kb-1",
                null, TaskStatus.PENDING, null, 0, OBJECT_MAPPER.createArrayNode(), null, 0, 3,
                null, null, null, null, null, null, false, null, null, 0, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Task(UUID.randomUUID(), TENANT, "ingest", "knowledge_base", "kb-1",
                null, TaskStatus.PENDING, null, 0, object(), OBJECT_MAPPER.getNodeFactory().textNode("x"), 0, 3,
                null, null, null, null, null, null, false, null, null, 0, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAttemptCountAboveMax() {
        assertThatThrownBy(() -> new Task(UUID.randomUUID(), TENANT, "ingest", "knowledge_base", "kb-1",
                null, TaskStatus.PENDING, null, 0, object(), null, 4, 3, null, null, null, null, null, null,
                false, null, null, 0, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHalfSetLock() {
        assertThatThrownBy(() -> new Task(UUID.randomUUID(), TENANT, "ingest", "knowledge_base", "kb-1",
                null, TaskStatus.RUNNING, null, 0, object(), null, 0, 3, null, "worker-1", null,
                null, null, null, false, null, null, 0, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCompletedBeforeStarted() {
        assertThatThrownBy(() -> new Task(UUID.randomUUID(), TENANT, "ingest", "knowledge_base", "kb-1",
                null, TaskStatus.SUCCEEDED, null, 100, object(), object(), 1, 3, null, null, null,
                null, null, null, false, NOW, NOW.minusSeconds(1), 1, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimingMarksRunningWithLeaseAndOneMoreAttempt() {
        Task claimed = validTask().claimed("worker-1", NOW, Duration.ofSeconds(60));

        assertThat(claimed.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(claimed.attemptCount()).isEqualTo(1);
        assertThat(claimed.startedAt()).isEqualTo(NOW);
        assertThat(claimed.lockedBy()).isEqualTo("worker-1");
        assertThat(claimed.lockedUntil()).isEqualTo(NOW.plusSeconds(60));
        assertThat(claimed.version()).isEqualTo(1);
        // The claim does not advance progress or touch the payload.
        assertThat(claimed.progress()).isZero();
        assertThat(claimed.payload()).isEqualTo(validTask().payload());
    }

    @Test
    void claimingARetryStartsANewAttemptWithCleanVisibleState() {
        Instant firstStartedAt = NOW.minusSeconds(120);
        Task retryWaiting = new Task(UUID.randomUUID(), TENANT, "ingest", "knowledge_base", "kb-1", null,
                TaskStatus.PENDING, "RETRY_WAIT", 80, object(), object(), 1, 3, NOW.minusSeconds(1),
                null, null, null, "VECTOR_UNAVAILABLE", "sanitized", true,
                firstStartedAt, null, 2, NOW.minusSeconds(180), NOW.minusSeconds(30));

        Task claimed = retryWaiting.claimed("worker-2", NOW, Duration.ofSeconds(60));

        assertThat(claimed.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(claimed.stage()).isNull();
        assertThat(claimed.progress()).isZero();
        assertThat(claimed.result()).isNull();
        assertThat(claimed.nextRetryAt()).isNull();
        assertThat(claimed.errorCode()).isNull();
        assertThat(claimed.errorMessage()).isNull();
        assertThat(claimed.retryable()).isFalse();
        assertThat(claimed.startedAt()).isEqualTo(firstStartedAt);
        assertThat(claimed.attemptCount()).isEqualTo(2);
    }

    @Test
    void claimingAnExhaustedTaskIsRejected() {
        Task exhausted = new Task(UUID.randomUUID(), TENANT, "ingest", "knowledge_base", "kb-1", null,
                TaskStatus.RUNNING, null, 0, object(), null, 3, 3, null, "worker-1", NOW.plusSeconds(30),
                null, null, null, false, NOW, null, 1, NOW, NOW);

        assertThatThrownBy(() -> exhausted.claimed("worker-1", NOW, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringNeverExposesPayloadOrResult() {
        Task task = new Task(UUID.randomUUID(), TENANT, "ingest", "knowledge_base", "kb-1", null,
                TaskStatus.PENDING, null, 0, OBJECT_MAPPER.createObjectNode().put("secret", "payload"),
                OBJECT_MAPPER.createObjectNode().put("secret", "result"), 0, 3, null, null, null,
                null, null, null, false, null, null, 0, NOW, NOW);

        assertThat(task.toString()).doesNotContain("payload").doesNotContain("result").doesNotContain("secret");
    }

    private static Task validTask() {
        return new Task(UUID.randomUUID(), TENANT, "ingest", "knowledge_base", "kb-1", "idem-1",
                TaskStatus.PENDING, null, 0, object(), null, 0, 3, null, null, null, null, null, null,
                false, null, null, 0, NOW, NOW);
    }

    private static JsonNode object() {
        return OBJECT_MAPPER.createObjectNode().put("source", "unit-test");
    }
}
