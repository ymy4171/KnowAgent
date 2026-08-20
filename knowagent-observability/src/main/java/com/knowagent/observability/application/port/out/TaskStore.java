package com.knowagent.observability.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.task.Task;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable task state.
 *
 * <p>Writes are optimistic: {@link #claim} and {@link #transition} are conditional
 * on the previous version and status, and the affected-row count tells the caller
 * whether the write won the race. The tenant id is always explicit - cross-tenant
 * ids simply match no row.
 */
public interface TaskStore {

    /** Inserts a task as part of the caller's transaction. */
    void save(Task task);

    /**
     * Reads a task strictly inside the tenant. Used by the authenticated task query;
     * a task from another tenant (or absent) is indistinguishable - empty.
     */
    Optional<Task> findById(TenantId tenantId, UUID taskId);

    /**
     * Claims one specific task for execution: PENDING becomes RUNNING with a lock
     * lease and one more attempt; a RUNNING task whose lease expired can be
     * reclaimed. A new attempt resets stage/progress and the previous attempt's
     * result/retry error fields. Returns the claimed task (post-claim version) or
     * empty when the task is not claimable.
     */
    Optional<Task> claim(TenantId tenantId, UUID taskId, String workerId, Instant now, Duration lease);

    /**
     * Updates a RUNNING task's stage/progress and renews its lease, guarded by the
     * current version, status and lock owner. Returns the post-update task or empty
     * when another execution has taken over.
     */
    Optional<Task> updateProgress(Task current, String stage, int progress, Instant now, Duration lease);

    /**
     * Applies a state transition guarded by {@code current}'s version and status.
     * Returns the number of rows updated (0 = lost a race or illegal transition).
     */
    int transition(Task current, TaskTransition transition);
}
