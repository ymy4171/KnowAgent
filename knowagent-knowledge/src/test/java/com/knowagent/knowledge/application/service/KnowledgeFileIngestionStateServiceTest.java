package com.knowagent.knowledge.application.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeBaseRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeChunkRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeFileRepository;
import com.knowagent.knowledge.chunk.ChunkIndexStatus;
import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.observability.application.port.out.InboxEventStore;
import com.knowagent.observability.application.port.out.TaskStore;
import com.knowagent.observability.application.port.out.TaskTransition;
import com.knowagent.observability.outbox.RetryPolicy;
import com.knowagent.observability.task.Task;
import com.knowagent.observability.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeFileIngestionStateServiceTest {

    private final TenantId tenant = TenantId.of(UUID.randomUUID());
    private final UUID fileId = UUID.randomUUID();
    private final KnowledgeFileIngestionCommand command = new KnowledgeFileIngestionCommand(
            tenant, UUID.randomUUID(), "knowledge-file-ingestion", fileId, "c".repeat(64));

    private KnowledgeFileRepository files;
    private KnowledgeBaseRepository knowledgeBases;
    private KnowledgeChunkRepository chunks;
    private TaskStore tasks;
    private InboxEventStore inbox;
    private KnowledgeFileIngestionStateService service;
    private KnowledgeFile file;
    private Task task;

    @BeforeEach
    void setUp() {
        files = mock(KnowledgeFileRepository.class);
        knowledgeBases = mock(KnowledgeBaseRepository.class);
        chunks = mock(KnowledgeChunkRepository.class);
        tasks = mock(TaskStore.class);
        inbox = mock(InboxEventStore.class);
        service = new KnowledgeFileIngestionStateService(files, knowledgeBases,
                chunks, tasks, inbox, new RetryPolicy(
                        Duration.ofSeconds(1).toMillis(), Duration.ofMinutes(1).toMillis()));
        file = mock(KnowledgeFile.class);
        task = mock(Task.class);
        when(files.findByTenantAndIdForUpdate(tenant, fileId)).thenReturn(Optional.of(file));
        when(file.knowledgeBaseId()).thenReturn(UUID.randomUUID());
        when(file.id()).thenReturn(fileId);
        when(file.status()).thenReturn(KnowledgeFileStatus.EMBEDDING);
        when(file.persistedTransitionTo(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(file);
        when(files.transitionStatus(any(), any())).thenReturn(true);
        when(tasks.transition(any(), any())).thenReturn(1);
        when(inbox.recordProcessed(any())).thenReturn(true);
        when(task.progress()).thenReturn(55);
        when(task.maxAttempts()).thenReturn(3);
    }

    @Test
    void retryableFailureReturnsTaskToPendingWithoutWritingInbox() {
        when(task.attemptCount()).thenReturn(1);

        KnowledgeFileIngestionOutcome outcome = service.fail(command, task,
                new IngestionFailure(ErrorCode.MODEL_RATE_LIMITED, "rate limited", true));

        assertThat(outcome).isEqualTo(KnowledgeFileIngestionOutcome.DEFERRED);
        TaskTransition transition = capturedTransition();
        assertThat(transition.targetStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(transition.stage()).isEqualTo("RETRY_WAIT");
        assertThat(transition.retryable()).isTrue();
        assertThat(transition.nextRetryAt()).isNotNull();
        verify(inbox, never()).recordProcessed(any());
        verify(chunks).transitionIndexStatus(tenant, file.knowledgeBaseId(), fileId,
                ChunkIndexStatus.PENDING, ChunkIndexStatus.FAILED, null,
                ErrorCode.MODEL_RATE_LIMITED.name(), "rate limited");
    }

    @Test
    void exhaustedRetryBudgetBecomesTerminalAndOnlyThenWritesInbox() {
        when(task.attemptCount()).thenReturn(3);

        KnowledgeFileIngestionOutcome outcome = service.fail(command, task,
                new IngestionFailure(ErrorCode.VECTOR_UNAVAILABLE, "unavailable", true));

        assertThat(outcome).isEqualTo(KnowledgeFileIngestionOutcome.TERMINAL_FAILURE);
        TaskTransition transition = capturedTransition();
        assertThat(transition.targetStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(transition.retryable()).isFalse();
        assertThat(transition.nextRetryAt()).isNull();
        verify(inbox).recordProcessed(any());
    }

    @Test
    void permanentParsingFailureNeverSchedulesAnAutomaticRetry() {
        when(task.attemptCount()).thenReturn(1);

        KnowledgeFileIngestionOutcome outcome = service.fail(command, task,
                new IngestionFailure(ErrorCode.CORRUPT_DOCUMENT, "corrupt", false));

        assertThat(outcome).isEqualTo(KnowledgeFileIngestionOutcome.TERMINAL_FAILURE);
        TaskTransition transition = capturedTransition();
        assertThat(transition.targetStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(transition.errorCode()).isEqualTo(ErrorCode.CORRUPT_DOCUMENT.name());
        assertThat(transition.retryable()).isFalse();
        verify(inbox).recordProcessed(any());
    }

    @Test
    void duplicateInboxShortCircuitsBeforeLockingTheFile() {
        when(inbox.wasProcessed(tenant, command.consumerName(), command.eventId())).thenReturn(true);

        var result = service.begin(command, "worker-a", Duration.ofMinutes(5));

        assertThat(result.kind()).isEqualTo(
                KnowledgeFileIngestionStateService.StartResult.Kind.ALREADY_PROCESSED);
        verify(files, never()).findByTenantAndIdForUpdate(any(), any());
        verify(tasks, never()).claim(any(), any(), any(), any(), any());
    }

    @Test
    void retryAfterIndexingFailureCanBeginSecondAttemptAtParsing() {
        UUID taskId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        Duration lease = Duration.ofMinutes(5);
        Instant firstStartedAt = Instant.now().minusSeconds(120);
        Task retryWaiting = new Task(taskId, tenant,
                KnowledgeFileSubmissionService.TASK_TYPE,
                KnowledgeFileSubmissionService.AGGREGATE_TYPE,
                fileId.toString(), null, TaskStatus.PENDING, "RETRY_WAIT", 80,
                JsonNodeFactory.instance.objectNode(), null, 1, 3,
                Instant.now().minusSeconds(1), null, null, null,
                ErrorCode.VECTOR_UNAVAILABLE.name(), "unavailable", true,
                firstStartedAt, null, 2, firstStartedAt, Instant.now().minusSeconds(30));
        Task claimed = retryWaiting.claimed("worker-b", Instant.now(), lease);

        KnowledgeFile failed = mock(KnowledgeFile.class);
        KnowledgeFile queued = mock(KnowledgeFile.class);
        KnowledgeFile parsing = mock(KnowledgeFile.class);
        KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);
        when(failed.id()).thenReturn(fileId);
        when(failed.knowledgeBaseId()).thenReturn(knowledgeBaseId);
        when(failed.status()).thenReturn(KnowledgeFileStatus.FAILED);
        when(failed.processingParams()).thenReturn(JsonNodeFactory.instance.objectNode()
                .put("task_id", taskId.toString()));
        when(failed.persistedTransitionTo(any(), any(), any(), anyBoolean(), any())).thenReturn(queued);
        when(queued.persistedTransitionTo(any(), any(), any(), anyBoolean(), any())).thenReturn(parsing);
        when(files.findByTenantAndIdForUpdate(tenant, fileId)).thenReturn(Optional.of(failed));
        when(tasks.findById(tenant, taskId)).thenReturn(Optional.of(retryWaiting));
        when(tasks.claim(eq(tenant), eq(taskId), eq("worker-b"), any(), eq(lease)))
                .thenReturn(Optional.of(claimed));
        when(knowledgeBases.findById(tenant, knowledgeBaseId)).thenReturn(Optional.of(knowledgeBase));
        when(tasks.updateProgress(eq(claimed), eq("PARSING"), eq(10), any(), eq(lease)))
                .thenAnswer(invocation -> Optional.of(claimed.progressed(
                        "PARSING", 10, invocation.getArgument(3), lease)));

        var result = service.begin(command, "worker-b", lease);

        assertThat(result.kind()).isEqualTo(KnowledgeFileIngestionStateService.StartResult.Kind.STARTED);
        assertThat(result.task().attemptCount()).isEqualTo(2);
        assertThat(result.task().stage()).isEqualTo("PARSING");
        assertThat(result.task().progress()).isEqualTo(10);
        assertThat(result.task().errorCode()).isNull();
        assertThat(result.task().retryable()).isFalse();
    }

    private TaskTransition capturedTransition() {
        ArgumentCaptor<TaskTransition> captor = ArgumentCaptor.forClass(TaskTransition.class);
        verify(tasks).transition(any(), captor.capture());
        return captor.getValue();
    }
}
