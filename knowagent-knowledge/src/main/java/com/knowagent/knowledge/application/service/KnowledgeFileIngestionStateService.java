package com.knowagent.knowledge.application.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
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
import com.knowagent.observability.inbox.InboxEvent;
import com.knowagent.observability.outbox.RetryPolicy;
import com.knowagent.observability.task.Task;
import com.knowagent.observability.task.TaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-only transaction slices used around non-transactional external calls. */
@Service
public class KnowledgeFileIngestionStateService {

    private final KnowledgeFileRepository files;
    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeChunkRepository chunks;
    private final TaskStore tasks;
    private final InboxEventStore inbox;
    private final RetryPolicy retryPolicy;

    @Autowired
    public KnowledgeFileIngestionStateService(KnowledgeFileRepository files,
                                              KnowledgeBaseRepository knowledgeBases,
                                              KnowledgeChunkRepository chunks,
                                              TaskStore tasks,
                                              InboxEventStore inbox) {
        this(files, knowledgeBases, chunks, tasks, inbox, RetryPolicy.DEFAULT);
    }

    KnowledgeFileIngestionStateService(KnowledgeFileRepository files,
                                       KnowledgeBaseRepository knowledgeBases,
                                       KnowledgeChunkRepository chunks,
                                       TaskStore tasks,
                                       InboxEventStore inbox,
                                       RetryPolicy retryPolicy) {
        this.files = Objects.requireNonNull(files);
        this.knowledgeBases = Objects.requireNonNull(knowledgeBases);
        this.chunks = Objects.requireNonNull(chunks);
        this.tasks = Objects.requireNonNull(tasks);
        this.inbox = Objects.requireNonNull(inbox);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
    }

    @Transactional
    public StartResult begin(KnowledgeFileIngestionCommand command, String workerId, Duration lease) {
        if (inbox.wasProcessed(command.tenantId(), command.consumerName(), command.eventId())) {
            return StartResult.alreadyProcessed();
        }

        KnowledgeFile file = files.findByTenantAndIdForUpdate(command.tenantId(), command.fileId())
                .orElse(null);
        if (file == null || !isExecutable(file.status())) {
            return StartResult.terminal();
        }
        UUID taskId = taskId(file).orElse(null);
        if (taskId == null) {
            return StartResult.terminal();
        }
        Task existing = tasks.findById(command.tenantId(), taskId).orElse(null);
        if (!matchesFileTask(existing, file)) {
            return StartResult.terminal();
        }

        Instant now = Instant.now();
        Optional<Task> claimed = tasks.claim(command.tenantId(), taskId, workerId, now, lease);
        if (claimed.isEmpty()) {
            return StartResult.deferred();
        }

        KnowledgeBase knowledgeBase = knowledgeBases.findById(command.tenantId(), file.knowledgeBaseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "A required ingestion resource does not exist."));

        KnowledgeFile current = file;
        if (current.status() != KnowledgeFileStatus.QUEUED) {
            if (current.status() != KnowledgeFileStatus.FAILED) {
                current = transition(current, KnowledgeFileStatus.FAILED,
                        ErrorCode.CONFLICT.name(), "A previous Worker lease expired.", true, now);
            }
            current = transition(current, KnowledgeFileStatus.QUEUED, null, null, false, now);
        }
        current = transition(current, KnowledgeFileStatus.PARSING, null, null, false, now);
        Task progressed = progress(claimed.get(), "PARSING", 10, now, lease);
        return StartResult.started(current, knowledgeBase, progressed);
    }

    @Transactional
    public Task advance(KnowledgeFileIngestionCommand command, Task task,
                        KnowledgeFileStatus expected, KnowledgeFileStatus target,
                        String stage, int progress, Duration lease) {
        Instant now = Instant.now();
        KnowledgeFile current = requireLockedFile(command, expected);
        transition(current, target, null, null, false, now);
        return progress(task, stage, progress, now, lease);
    }

    @Transactional
    public Task beginIndexing(KnowledgeFileIngestionCommand command, Task task,
                              int chunkCount, String modelSpec, Duration lease) {
        Instant now = Instant.now();
        KnowledgeFile current = requireLockedFile(command, KnowledgeFileStatus.EMBEDDING);
        int updated = chunks.transitionIndexStatus(command.tenantId(), current.knowledgeBaseId(), current.id(),
                ChunkIndexStatus.PENDING, ChunkIndexStatus.INDEXING, modelSpec, null, null);
        if (updated != chunkCount) {
            throw conflict("The chunk set changed before indexing.");
        }
        transition(current, KnowledgeFileStatus.INDEXING, null, null, false, now);
        return progress(task, "INDEXING", 80, now, lease);
    }

    @Transactional
    public void complete(KnowledgeFileIngestionCommand command, Task task, int chunkCount, String modelSpec) {
        Instant now = Instant.now();
        KnowledgeFile current = requireLockedFile(command, KnowledgeFileStatus.INDEXING);
        int updated = chunks.transitionIndexStatus(command.tenantId(), current.knowledgeBaseId(), current.id(),
                ChunkIndexStatus.INDEXING, ChunkIndexStatus.READY, modelSpec, null, null);
        if (updated != chunkCount) {
            throw conflict("The chunk set changed before completion.");
        }
        transition(current, KnowledgeFileStatus.READY, null, null, false, now);
        var result = JsonNodeFactory.instance.objectNode().put("file_id", command.fileId().toString());
        if (tasks.transition(task, new TaskTransition(TaskStatus.SUCCEEDED, "READY", 100,
                result, null, null, false, null)) != 1) {
            throw conflict("The task lease was lost before completion.");
        }
        boolean inserted = inbox.recordProcessed(new InboxEvent(
                UUID.randomUUID(), command.tenantId(), command.consumerName(), command.eventId(),
                KnowledgeFileSubmissionService.EVENT_TYPE, command.payloadHash(), now));
        if (!inserted) {
            throw conflict("The event was completed concurrently.");
        }
    }

    @Transactional
    public KnowledgeFileIngestionOutcome fail(KnowledgeFileIngestionCommand command,
                                              Task task,
                                              IngestionFailure failure) {
        Instant now = Instant.now();
        KnowledgeFile current = files.findByTenantAndIdForUpdate(command.tenantId(), command.fileId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "A required ingestion resource does not exist."));
        boolean willRetry = failure.retryable() && task.attemptCount() < task.maxAttempts();
        if (current.status() != KnowledgeFileStatus.FAILED) {
            if (!current.status().canTransitionTo(KnowledgeFileStatus.FAILED)) {
                throw conflict("The file can no longer fail from its current state.");
            }
            current = transition(current, KnowledgeFileStatus.FAILED, failure.errorCode().name(),
                    failure.message(), willRetry, now);
        }
        chunks.transitionIndexStatus(command.tenantId(), current.knowledgeBaseId(), current.id(),
                ChunkIndexStatus.PENDING, ChunkIndexStatus.FAILED, null,
                failure.errorCode().name(), failure.message());
        chunks.transitionIndexStatus(command.tenantId(), current.knowledgeBaseId(), current.id(),
                ChunkIndexStatus.INDEXING, ChunkIndexStatus.FAILED, null,
                failure.errorCode().name(), failure.message());

        TaskStatus target = willRetry ? TaskStatus.PENDING : TaskStatus.FAILED;
        Instant nextRetryAt = willRetry ? retryPolicy.nextRetryAt(now, task.attemptCount()) : null;
        if (tasks.transition(task, new TaskTransition(target,
                willRetry ? "RETRY_WAIT" : "FAILED", task.progress(), null,
                failure.errorCode().name(), failure.message(), willRetry, nextRetryAt)) != 1) {
            throw conflict("The task lease was lost while recording failure.");
        }
        if (willRetry) {
            return KnowledgeFileIngestionOutcome.DEFERRED;
        }
        boolean inserted = inbox.recordProcessed(new InboxEvent(
                UUID.randomUUID(), command.tenantId(), command.consumerName(), command.eventId(),
                KnowledgeFileSubmissionService.EVENT_TYPE, command.payloadHash(), now));
        if (!inserted) {
            throw conflict("The event was completed concurrently.");
        }
        return KnowledgeFileIngestionOutcome.TERMINAL_FAILURE;
    }

    private KnowledgeFile requireLockedFile(KnowledgeFileIngestionCommand command,
                                            KnowledgeFileStatus expected) {
        KnowledgeFile file = files.findByTenantAndIdForUpdate(command.tenantId(), command.fileId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "A required ingestion resource does not exist."));
        if (file.status() != expected) {
            throw conflict("The file state changed concurrently.");
        }
        return file;
    }

    private KnowledgeFile transition(KnowledgeFile current, KnowledgeFileStatus target,
                                     String errorCode, String errorMessage, boolean retryable, Instant now) {
        KnowledgeFile changed = current.persistedTransitionTo(
                target, errorCode, errorMessage, retryable, now);
        if (!files.transitionStatus(current, changed)) {
            throw conflict("The file state changed concurrently.");
        }
        return changed;
    }

    private Task progress(Task task, String stage, int value, Instant now, Duration lease) {
        return tasks.updateProgress(task, stage, value, now, lease)
                .orElseThrow(() -> conflict("The task lease was lost while updating progress."));
    }

    private static Optional<UUID> taskId(KnowledgeFile file) {
        String raw = file.processingParams().path("task_id").asText(null);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static boolean matchesFileTask(Task task, KnowledgeFile file) {
        return task != null
                && KnowledgeFileSubmissionService.TASK_TYPE.equals(task.taskType())
                && KnowledgeFileSubmissionService.AGGREGATE_TYPE.equals(task.aggregateType())
                && file.id().toString().equals(task.aggregateId())
                && !task.status().isTerminal();
    }

    private static boolean isExecutable(KnowledgeFileStatus status) {
        return status == KnowledgeFileStatus.QUEUED
                || status == KnowledgeFileStatus.FAILED
                || status == KnowledgeFileStatus.PARSING
                || status == KnowledgeFileStatus.CHUNKING
                || status == KnowledgeFileStatus.EMBEDDING
                || status == KnowledgeFileStatus.INDEXING;
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    public record StartResult(Kind kind, KnowledgeFile file, KnowledgeBase knowledgeBase, Task task) {
        enum Kind { STARTED, ALREADY_PROCESSED, DEFERRED, TERMINAL }

        static StartResult started(KnowledgeFile file, KnowledgeBase kb, Task task) {
            return new StartResult(Kind.STARTED, file, kb, task);
        }

        static StartResult alreadyProcessed() {
            return new StartResult(Kind.ALREADY_PROCESSED, null, null, null);
        }

        static StartResult deferred() {
            return new StartResult(Kind.DEFERRED, null, null, null);
        }

        static StartResult terminal() {
            return new StartResult(Kind.TERMINAL, null, null, null);
        }
    }
}
