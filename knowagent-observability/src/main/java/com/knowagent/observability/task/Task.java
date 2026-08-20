package com.knowagent.observability.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.common.tenant.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable, user-visible asynchronous task state. The row is written together with
 * the outbox event in the business transaction; the worker later claims it (with a
 * lock lease) and transitions it to a terminal state.
 *
 * <p>Immutable: {@link #claimed} and the transition helpers return a new instance
 * with a bumped {@link #version()}, so every write can be guarded by the previous
 * version and status.
 */
public record Task(
        UUID id,
        TenantId tenantId,
        String taskType,
        String aggregateType,
        String aggregateId,
        String idempotencyKey,
        TaskStatus status,
        String stage,
        int progress,
        JsonNode payload,
        JsonNode result,
        int attemptCount,
        int maxAttempts,
        Instant nextRetryAt,
        String lockedBy,
        Instant lockedUntil,
        Instant cancelRequestedAt,
        String errorCode,
        String errorMessage,
        boolean retryable,
        Instant startedAt,
        Instant completedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public Task {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (taskType == null || taskType.isBlank()) {
            throw new IllegalArgumentException("taskType must not be blank");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("progress must be in [0, 100]");
        }
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        if (result != null && !result.isObject()) {
            throw new IllegalArgumentException("result must be a JSON object");
        }
        if (attemptCount < 0 || maxAttempts < 1 || attemptCount > maxAttempts) {
            throw new IllegalArgumentException("attemptCount must be in [0, maxAttempts] with maxAttempts >= 1");
        }
        if ((lockedBy == null) != (lockedUntil == null)) {
            throw new IllegalArgumentException("lockedBy and lockedUntil must both be set or both be null");
        }
        if (completedAt != null && startedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /**
     * Claims this task for execution: RUNNING, a fresh lease, one more attempt.
     * Each attempt restarts the user-visible execution state so a retry can run the
     * full pipeline from its first stage without violating monotonic progress.
     * A task that already burned its attempt budget (attemptCount == maxAttempts)
     * is rejected - the last failure must resolve to a terminal state, never another
     * retry. The claim SQL additionally guards {@code attempt_count < max_attempts},
     * so an exhausted row is simply not claimable.
     */
    public Task claimed(String workerId, Instant now, Duration lease) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(lease, "lease must not be null");
        if (attemptCount >= maxAttempts) {
            throw new IllegalArgumentException("Cannot claim a task that has exhausted its attempts: "
                    + "attemptCount=" + attemptCount + ", maxAttempts=" + maxAttempts);
        }
        return new Task(id, tenantId, taskType, aggregateType, aggregateId, idempotencyKey,
                TaskStatus.RUNNING, null, 0, payload, null,
                attemptCount + 1, maxAttempts, null, workerId, now.plus(lease),
                cancelRequestedAt, null, null, false, startedAt == null ? now : startedAt, null,
                version + 1, createdAt, now);
    }

    /**
     * Advances the visible stage/progress of a RUNNING task and renews its existing
     * execution lease. This is deliberately not a status transition: long-running
     * parsing/model/vector calls keep the same owner while PostgreSQL remains the UI
     * source of truth for progress.
     */
    public Task progressed(String nextStage, int nextProgress, Instant now, Duration lease) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(lease, "lease must not be null");
        if (status != TaskStatus.RUNNING || lockedBy == null) {
            throw new IllegalStateException("Only a leased RUNNING task can update progress");
        }
        if (nextStage == null || nextStage.isBlank()) {
            throw new IllegalArgumentException("stage must not be blank");
        }
        if (nextProgress < progress || nextProgress > 100) {
            throw new IllegalArgumentException("progress must be monotonic and in [0, 100]");
        }
        return new Task(id, tenantId, taskType, aggregateType, aggregateId, idempotencyKey,
                status, nextStage, nextProgress, payload, result, attemptCount, maxAttempts,
                nextRetryAt, lockedBy, now.plus(lease), cancelRequestedAt, errorCode,
                errorMessage, retryable, startedAt, completedAt, version + 1, createdAt, now);
    }

    /**
     * Deliberately omits {@code payload}, {@code result} and {@code errorMessage}
     * (rule: secrets / raw content never surface in {@code toString}).
     */
    @Override
    public String toString() {
        return "Task[id=" + id + ", tenantId=" + tenantId
                + ", taskType=" + taskType + ", aggregateType=" + aggregateType
                + ", aggregateId=" + aggregateId + ", idempotencyKey=" + idempotencyKey
                + ", status=" + status + ", stage=" + stage + ", progress=" + progress
                + ", attemptCount=" + attemptCount + ", maxAttempts=" + maxAttempts
                + ", nextRetryAt=" + nextRetryAt + ", lockedBy=" + lockedBy
                + ", lockedUntil=" + lockedUntil + ", cancelRequestedAt=" + cancelRequestedAt
                + ", errorCode=" + errorCode + ", retryable=" + retryable
                + ", startedAt=" + startedAt + ", completedAt=" + completedAt
                + ", version=" + version + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt + "]";
    }
}
