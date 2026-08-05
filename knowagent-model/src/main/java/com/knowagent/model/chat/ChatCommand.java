package com.knowagent.model.chat;

import java.util.List;
import java.util.Objects;

public record ChatCommand(
        String modelSpec,
        String systemPrompt,
        List<ChatMessage> messages,
        ModelOptions options
) {

    public ChatCommand {
        Objects.requireNonNull(modelSpec, "modelSpec must not be null");
        messages = List.copyOf(messages);
        options = options == null ? ModelOptions.defaults() : options;
    }
}

