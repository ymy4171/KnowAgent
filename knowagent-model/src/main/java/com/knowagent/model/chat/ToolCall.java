package com.knowagent.model.chat;

import java.util.Objects;

public record ToolCall(
        String id,
        String name,
        String argumentsJson
) {

    public ToolCall {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(argumentsJson, "argumentsJson must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (argumentsJson.isBlank()) {
            throw new IllegalArgumentException("argumentsJson must not be blank");
        }
    }
}
