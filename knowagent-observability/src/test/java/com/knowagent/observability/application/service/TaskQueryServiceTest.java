package com.knowagent.observability.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.application.port.out.TaskStore;
import com.knowagent.observability.application.port.out.TaskTransition;
import com.knowagent.observability.task.Task;
import com.knowagent.observability.task.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TaskQueryServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());

    private final FakeTaskStore store = new FakeTaskStore();
    private final TaskQueryService service = new TaskQueryService(store);

    @Test
    void returnsTheTaskScopedToTheGivenTenant() {
        Task task = validTask();
        store.found = Optional.of(task);

        Task result = service.get(TENANT, task.id());

        assertThat(result).isEqualTo(task);
        assertThat(store.lastTenant).isEqualTo(TENANT);
        assertThat(store.lastTaskId).isEqualTo(task.id());
    }

    @Test
    void missingTaskIsANonRevealingNotFound() {
        store.found = Optional.empty();

        BusinessException ex = catchThrowableOfType(() -> service.get(TENANT, UUID.randomUUID()),
                BusinessException.class);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static Task validTask() {
        return new Task(UUID.randomUUID(), TENANT, "ingest", "knowledge_base", "kb-1", "idem-1",
                TaskStatus.PENDING, null, 0, object(), null, 0, 3, null, null, null, null, null, null,
                false, null, null, 0, Instant.EPOCH, Instant.EPOCH);
    }

    private static JsonNode object() {
        return OBJECT_MAPPER.createObjectNode().put("source", "unit-test");
    }

    private static final class FakeTaskStore implements TaskStore {
        Optional<Task> found = Optional.empty();
        TenantId lastTenant;
        UUID lastTaskId;

        @Override
        public void save(Task task) {
            throw new UnsupportedOperationException("not used by the query service");
        }

        @Override
        public Optional<Task> findById(TenantId tenantId, UUID taskId) {
            lastTenant = tenantId;
            lastTaskId = taskId;
            return found;
        }

        @Override
        public Optional<Task> claim(TenantId tenantId, UUID taskId, String workerId, Instant now, Duration lease) {
            throw new UnsupportedOperationException("not used by the query service");
        }

        @Override
        public Optional<Task> updateProgress(Task current, String stage, int progress, Instant now, Duration lease) {
            throw new UnsupportedOperationException("not used by the query service");
        }

        @Override
        public int transition(Task current, TaskTransition transition) {
            throw new UnsupportedOperationException("not used by the query service");
        }
    }
}
