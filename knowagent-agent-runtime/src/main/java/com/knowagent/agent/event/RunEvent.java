package com.knowagent.agent.event;

import com.knowagent.common.event.DomainEvent;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RunEvent(
        UUID eventId,
        UUID runId,
        Type type,
        String data,
        Map<String, String> metadata,
        Instant occurredAt
) implements DomainEvent {

    public RunEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override
    public String aggregateId() {
        return runId.toString();
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
