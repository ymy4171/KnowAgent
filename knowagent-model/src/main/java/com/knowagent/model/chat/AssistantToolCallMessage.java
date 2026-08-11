package com.knowagent.model.chat;

import java.util.List;
import java.util.Objects;

public record AssistantToolCallMessage(
        String content,
        List<ToolCall> toolCalls
) implements ChatMessage {

    public AssistantToolCallMessage {
        content = content == null ? "" : content;
        Objects.requireNonNull(toolCalls, "toolCalls must not be null");
        toolCalls = List.copyOf(toolCalls);
        if (toolCalls.isEmpty()) {
            throw new IllegalArgumentException("toolCalls must not be empty");
        }
    }

    @Override
    public ChatRole role() {
        return ChatRole.ASSISTANT;
    }
}
