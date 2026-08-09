package com.knowagent.agent.run;

public enum AgentRequestStatus {
    QUEUED(false),
    DISPATCHED(false),
    CANCELLED(true),
    REJECTED(true),
    FAILED(true);

    private final boolean terminal;

    AgentRequestStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
