package com.knowagent.observability.application.port.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.observability.application.service.ErrorMessageSanitizer;
import com.knowagent.observability.task.TaskStatus;

import java.time.Instant;

/**
 * The target state of one {@link com.knowagent.observability.task.Task} transition,
 * separate from the current row so {@link TaskStore#transition} can be guarded by
 * the previous version/status while applying the new values.
 *
 * <p>{@code errorMessage} is redacted/stripped/truncated on construction by
 * {@link ErrorMessageSanitizer} - the single shared sanitization boundary for Task
 * and Outbox error text - so the persistence store never receives a raw message.
 */
public record TaskTransition(
        TaskStatus targetStatus,
        String stage,
        int progress,
        JsonNode result,
        String errorCode,
        String errorMessage,
        boolean retryable,
        Instant nextRetryAt) {

    public TaskTransition {
        if (targetStatus == null) {
            throw new IllegalArgumentException("targetStatus must not be null");
        }
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("progress must be in [0, 100]");
        }
        if (result != null && !result.isObject()) {
            throw new IllegalArgumentException("result must be a JSON object");
        }
        errorMessage = ErrorMessageSanitizer.sanitize(errorMessage);
    }

    /**
     * Deliberately omits {@code result} and {@code errorMessage}; both may contain
     * document content or provider diagnostics that must not be written to logs.
     */
    @Override
    public String toString() {
        return "TaskTransition[targetStatus=" + targetStatus
                + ", stage=" + stage + ", progress=" + progress
                + ", errorCode=" + errorCode + ", retryable=" + retryable
                + ", nextRetryAt=" + nextRetryAt + "]";
    }
}
