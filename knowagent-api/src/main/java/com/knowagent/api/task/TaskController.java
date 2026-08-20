package com.knowagent.api.task;

import com.knowagent.api.task.dto.TaskResponse;
import com.knowagent.observability.application.service.TaskQueryService;
import com.knowagent.security.principal.TenantPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Asynchronous task status endpoints. The tenant id is always read from the
 * authenticated principal; reads require {@code TASK_READ}. A task that belongs to
 * another tenant (or does not exist) surfaces as a 404.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskQueryService tasks;

    public TaskController(TaskQueryService tasks) {
        this.tasks = tasks;
    }

    @PreAuthorize("hasAuthority('TASK_READ')")
    @GetMapping("/{id}")
    public TaskResponse get(@AuthenticationPrincipal TenantPrincipal principal,
                            @PathVariable UUID id) {
        return TaskResponse.from(tasks.get(principal.tenantId(), id));
    }
}
