package com.knowagent.observability.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.application.port.out.TaskStore;
import com.knowagent.observability.task.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-scoped read of a task, serving {@code GET /api/v1/tasks/{id}}.
 *
 * <p>The tenant id is always supplied by the caller from the authenticated
 * principal. A task that cannot be seen - absent or belonging to another tenant -
 * is a 404, matching the rule that a resource a caller cannot see must not be
 * distinguishable.
 */
@Service
public class TaskQueryService {

    private final TaskStore tasks;

    public TaskQueryService(TaskStore tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks must not be null");
    }

    @Transactional(readOnly = true)
    public Task get(TenantId tenantId, UUID taskId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        return tasks.findById(tenantId, taskId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "The requested task does not exist."));
    }
}
