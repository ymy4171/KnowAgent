package com.knowagent.agent.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RunEvent(
        String eventId,
        UUID runId,
        Type type,
        String data,
        Map<String, String> metadata,
        Instant occurredAt
) {

    public RunEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public enum Type {
        RUN_STARTED,
        MODEL_DELTA,
        TOOL_STARTED,
        TOOL_COMPLETED,
        APPROVAL_REQUIRED,
        RUN_INTERRUPTED,
        RUN_COMPLETED,
        RUN_FAILED,
        RUN_CANCELLED
    }
}

