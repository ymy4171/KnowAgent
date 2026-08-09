package com.knowagent.model.chat;

public sealed interface ChatMessage permits TextChatMessage, AssistantToolCallMessage, ToolResultMessage {

    ChatRole role();
}
