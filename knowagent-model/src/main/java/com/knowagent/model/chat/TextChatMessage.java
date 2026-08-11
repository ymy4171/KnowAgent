package com.knowagent.model.chat;

import java.util.Objects;

public record TextChatMessage(
        ChatRole role,
        String content
) implements ChatMessage {

    public TextChatMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (role == ChatRole.TOOL) {
            throw new IllegalArgumentException("TOOL role requires ToolResultMessage");
        }
    }
}
