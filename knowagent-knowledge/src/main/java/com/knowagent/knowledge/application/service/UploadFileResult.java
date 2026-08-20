package com.knowagent.knowledge.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.knowledge.file.KnowledgeFile;

import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of {@link KnowledgeFileService#upload}. {@code replayed} is true when an
 * {@code Idempotency-Key} matched an existing upload with identical content: no second
 * file, task or outbox event was created and {@code file} is the original row. The
 * ingest task id is read from the file's processing params (present for every upload
 * written by this task).
 */
public record UploadFileResult(
        KnowledgeFile file,
        UUID taskId,
        boolean replayed
) {

    public UploadFileResult {
        Objects.requireNonNull(file, "file must not be null");
        taskId = extractTaskId(file.processingParams());
    }

    private static UUID extractTaskId(JsonNode processingParams) {
        if (processingParams == null || !processingParams.hasNonNull("task_id")) {
            return null;
        }
        JsonNode taskIdNode = processingParams.get("task_id");
        if (!taskIdNode.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(taskIdNode.asText());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
