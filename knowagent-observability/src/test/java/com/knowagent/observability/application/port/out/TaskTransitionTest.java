package com.knowagent.observability.application.port.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.observability.application.service.ErrorMessageSanitizer;
import com.knowagent.observability.task.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the Task-side of the shared error-sanitization boundary: a
 * {@link TaskTransition} can never carry a raw credential, because the compact
 * constructor routes {@code errorMessage} through {@link ErrorMessageSanitizer}.
 */
class TaskTransitionTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void sanitizesErrorMessageOnConstruction() {
        TaskTransition transition = new TaskTransition(
                TaskStatus.FAILED, "ingest", 100, object(), "ERR",
                "boom api_key=sk-abc123def456 Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.sig",
                false, null);

        assertThat(transition.errorMessage())
                .doesNotContain("sk-abc123def456")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .contains("api_key=<redacted>")
                .contains("Authorization: Bearer <redacted>");
    }

    @Test
    void nullErrorMessageStaysNull() {
        TaskTransition transition = new TaskTransition(
                TaskStatus.FAILED, "ingest", 100, object(), "ERR", null, false, null);

        assertThat(transition.errorMessage()).isNull();
    }

    @Test
    void controlCharactersAreStrippedAndMessageTruncated() {
        String longMessage = "boom\000" + "x".repeat(10_000);
        TaskTransition transition = new TaskTransition(
                TaskStatus.FAILED, "ingest", 100, object(), "ERR", longMessage, false, null);

        assertThat(transition.errorMessage())
                .doesNotContain("\000")
                .hasSize(ErrorMessageSanitizer.DEFAULT_MAX_LENGTH);
    }

    @Test
    void toStringNeverExposesResultOrErrorMessage() {
        JsonNode result = OBJECT_MAPPER.createObjectNode().put("secret", "result-secret");
        TaskTransition transition = new TaskTransition(
                TaskStatus.FAILED, "ingest", 100, result, "PARSER_FAILED",
                "provider diagnostic password=hunter2", false, null);

        assertThat(transition.toString())
                .contains("targetStatus=FAILED", "stage=ingest", "progress=100", "errorCode=PARSER_FAILED")
                .doesNotContain("result-secret", "hunter2", "provider diagnostic", "result=", "errorMessage=");
    }

    private static JsonNode object() {
        return OBJECT_MAPPER.createObjectNode().put("source", "unit-test");
    }
}
