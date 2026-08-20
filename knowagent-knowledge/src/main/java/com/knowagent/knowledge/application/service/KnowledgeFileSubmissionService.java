package com.knowagent.knowledge.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.application.port.out.KnowledgeBaseRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeFileRepository;
import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.observability.application.service.SubmitTaskCommand;
import com.knowagent.observability.application.service.TaskSubmission;
import com.knowagent.observability.application.service.TaskSubmissionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The transactional boundary for an uploaded file: writes the {@code knowledge_files}
 * row (already {@code QUEUED}) and the ingest task plus its outbox event in a single
 * Spring transaction ({@code REQUIRED} joins the caller's transaction that is still
 * open after the object reached object storage).
 *
 * <p>The task/event payloads carry only the file id, never the object key or any file
 * content; the future worker reconstructs the key with
 * {@code StorageKeys.knowledgeFileSource}. The task's own idempotency key is left null
 * because the upload idempotency key is scoped to {@code (tenant, knowledge base)}
 * while the task unique index is scoped to {@code (tenant, task type)} - reusing it
 * would collide across knowledge bases. The file row itself is the idempotency anchor;
 * a replayed upload never reaches this service.
 */
@Service
public class KnowledgeFileSubmissionService {

    public static final String TASK_TYPE = "knowledge_file.ingest";
    public static final String AGGREGATE_TYPE = "knowledge_file";
    public static final String EVENT_TYPE = "knowledge_file.ingested";

    private static final int MAX_ATTEMPTS = 3;
    private static final int EVENT_MAX_RETRIES = 3;

    private final TaskSubmission taskSubmission;
    private final KnowledgeFileRepository files;
    private final KnowledgeBaseRepository knowledgeBases;

    public KnowledgeFileSubmissionService(TaskSubmission taskSubmission, KnowledgeFileRepository files,
                                          KnowledgeBaseRepository knowledgeBases) {
        this.taskSubmission = Objects.requireNonNull(taskSubmission, "taskSubmission must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.knowledgeBases = Objects.requireNonNull(knowledgeBases, "knowledgeBases must not be null");
    }

    /**
     * Creates the file ({@code UPLOADED -> QUEUED}) and submits the ingest task/event
     * atomically. Only the terminal {@code QUEUED} row is persisted.
     */
    @Transactional
    public KnowledgeFile submitUpload(PersistUploadedFileCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Instant now = Instant.now();

        KnowledgeBase knowledgeBase = knowledgeBases
                .findByIdForKeyShare(command.tenantId(), command.knowledgeBaseId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "The requested resource does not exist."));
        if (knowledgeBase.status() != KnowledgeBaseStatus.ACTIVE) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "The knowledge base is not active; files cannot be uploaded.");
        }

        TaskSubmissionResult submitted = taskSubmission.submit(new SubmitTaskCommand(
                command.tenantId(),
                TASK_TYPE,
                AGGREGATE_TYPE,
                command.fileId().toString(),
                null,
                payload(command.fileId()),
                MAX_ATTEMPTS,
                EVENT_TYPE,
                payload(command.fileId()),
                null,
                EVENT_MAX_RETRIES));

        ObjectNode processingParams = (ObjectNode) KnowledgeFile.emptyProcessingParams();
        processingParams.put("task_id", submitted.taskId().toString());
        processingParams.put("outbox_event_id", submitted.outboxEventId().toString());

        KnowledgeFile uploaded = new KnowledgeFile(
                command.fileId(), command.tenantId(), command.knowledgeBaseId(), null,
                command.uploadIdempotencyKey(), command.displayName(), command.originalFilename(),
                command.objectKey(), command.contentType(), command.fileExtension(),
                command.sha256(), command.fileSizeBytes(), KnowledgeFileStatus.UPLOADED,
                0, 0, processingParams, KnowledgeFile.emptyMetadata(), null, null, false,
                command.actorId(), command.actorId(), 0L, now, now, null);
        KnowledgeFile queued = uploaded.transitionTo(KnowledgeFileStatus.QUEUED);
        files.save(queued);
        return queued;
    }

    private static JsonNode payload(UUID fileId) {
        return JsonNodeFactory.instance.objectNode().put("file_id", fileId.toString());
    }
}
