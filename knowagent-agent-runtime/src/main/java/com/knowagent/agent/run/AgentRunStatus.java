package com.knowagent.agent.run;

public enum AgentRunStatus {
    PENDING,
    RUNNING,
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}

