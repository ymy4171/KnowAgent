package com.knowagent.observability.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.application.port.out.TaskStore;
import com.knowagent.observability.application.port.out.TaskTransition;
import com.knowagent.observability.infrastructure.persistence.converter.TaskPersistenceConverter;
import com.knowagent.observability.infrastructure.persistence.entity.TaskPo;
import com.knowagent.observability.infrastructure.persistence.mapper.TaskMapper;
import com.knowagent.observability.task.Task;
import com.knowagent.observability.task.TaskStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisTaskStore implements TaskStore {

    private final TaskMapper mapper;

    public MyBatisTaskStore(TaskMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public void save(Task task) {
        mapper.insert(TaskPersistenceConverter.toPersistence(task));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Task> findById(TenantId tenantId, UUID taskId) {
        TaskPo row = mapper.selectByIdAndTenant(tenantId.value(), taskId);
        return Optional.ofNullable(row).map(TaskPersistenceConverter::toDomain);
    }

    @Override
    @Transactional
    public Optional<Task> claim(TenantId tenantId, UUID taskId, String workerId, Instant now, Duration lease) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(lease, "lease must not be null");

        TaskPo row = mapper.selectByIdAndTenantForUpdate(tenantId.value(), taskId);
        if (row == null) {
            return Optional.empty();
        }
        boolean claimable = row.getStatus() == TaskStatus.PENDING
                || (row.getStatus() == TaskStatus.RUNNING
                && row.getLockedUntil() != null && row.getLockedUntil().toInstant().isBefore(now));
        if (!claimable) {
            return Optional.empty();
        }
        int updated = mapper.claimForExecution(tenantId.value(), taskId, row.getVersion(), workerId,
                offset(now), offset(now.plus(lease)));
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(TaskPersistenceConverter.toDomain(row).claimed(workerId, now, lease));
    }

    @Override
    @Transactional
    public Optional<Task> updateProgress(Task current, String stage, int progress, Instant now, Duration lease) {
        Objects.requireNonNull(current, "current must not be null");
        Task target = current.progressed(stage, progress, now, lease);
        int updated = mapper.updateRunningProgress(
                current.tenantId().value(), current.id(), current.version(), current.lockedBy(),
                target.stage(), target.progress(), offset(target.lockedUntil()));
        return updated == 1 ? Optional.of(target) : Optional.empty();
    }

    @Override
    @Transactional
    public int transition(Task current, TaskTransition transition) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(transition, "transition must not be null");
        if (!current.status().canTransitionTo(transition.targetStatus())) {
            return 0;
        }
        // An exhausted task must resolve to a terminal state (FAILED/SUCCEEDED/
        // CANCELLED): never allow it to be scheduled for another retry. The final
        // failure must go to FAILED, not back to PENDING.
        if (transition.targetStatus() == TaskStatus.PENDING && current.attemptCount() >= current.maxAttempts()) {
            return 0;
        }
        return mapper.transitionTask(
                current.tenantId().value(), current.id(), current.version(), current.status(),
                transition.targetStatus(), transition.stage(), transition.progress(), transition.result(),
                transition.errorCode(), transition.errorMessage(), transition.retryable(),
                offset(transition.nextRetryAt()));
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
