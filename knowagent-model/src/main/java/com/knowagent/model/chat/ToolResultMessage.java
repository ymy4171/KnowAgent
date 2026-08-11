package com.knowagent.model.chat;

import java.util.Objects;

public record ToolResultMessage(
        String toolCallId,
        String toolName,
        String content
) implements ChatMessage {

    public ToolResultMessage {
        Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
    }

    @Override
    public ChatRole role() {
        return ChatRole.TOOL;
    }
}
