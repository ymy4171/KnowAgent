package com.knowagent.agent.run;

public record RunResult(
        AgentRunStatus status,
        String assistantMessage
) {
}

