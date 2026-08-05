package com.knowagent.agent.run;

public enum AgentRequestStatus {
    QUEUED,
    DISPATCHED,
    CANCELLED,
    REJECTED,
    FAILED;

    public boolean isTerminal() {
        return this == CANCELLED || this == REJECTED || this == FAILED;
    }
}

