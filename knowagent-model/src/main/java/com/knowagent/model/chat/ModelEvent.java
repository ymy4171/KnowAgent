package com.knowagent.model.chat;

import java.util.Map;
import java.util.Objects;

public record ModelEvent(
        Type type,
        String content,
        Map<String, String> metadata
) {

    public ModelEvent {
        Objects.requireNonNull(type, "type must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public enum Type {
        CONTENT_DELTA,
        TOOL_CALL,
        USAGE,
        COMPLETED
    }
}

