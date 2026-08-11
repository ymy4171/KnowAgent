package com.knowagent.model.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ChatMessageTest {

    @Test
    void textMessagesCannotPretendToBeToolResults() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TextChatMessage(ChatRole.TOOL, "result"));
    }

    @Test
    void assistantMessagePreservesMultipleToolCallsInOrder() {
        var first = new ToolCall("call-1", "search", "{\"query\":\"KnowAgent\"}");
        var second = new ToolCall("call-2", "fetch", "{\"id\":\"doc-1\"}");

        var message = new AssistantToolCallMessage(null, List.of(first, second));

        assertThat(message.role()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(message.content()).isEmpty();
        assertThat(message.toolCalls()).containsExactly(first, second);
    }

    @Test
    void toolResultRequiresCorrelationIdentity() {
        assertThatNullPointerException().isThrownBy(() -> new ToolResultMessage(null, "search", "result"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolResultMessage(" ", "search", "result"));

        var message = new ToolResultMessage("call-1", "search", "result");
        assertThat(message.role()).isEqualTo(ChatRole.TOOL);
        assertThat(message.toolCallId()).isEqualTo("call-1");
    }

    @Test
    void toolCallListMustNotBeNullOrEmpty() {
        assertThatNullPointerException().isThrownBy(() -> new AssistantToolCallMessage("", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new AssistantToolCallMessage("", List.of()));
    }
}
