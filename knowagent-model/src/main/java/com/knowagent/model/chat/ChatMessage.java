package com.knowagent.model.chat;

import java.util.Objects;

public record ChatMessage(ChatRole role, String content) {

    public ChatMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}

