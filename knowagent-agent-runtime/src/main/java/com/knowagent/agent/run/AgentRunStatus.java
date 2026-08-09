package com.knowagent.agent.run;

import java.util.Objects;

public enum AgentRunStatus {
    PENDING(false),
    RUNNING(false),
    INTERRUPTED(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true);

    private final boolean terminal;

    AgentRunStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean canTransitionTo(AgentRunStatus target) {
        Objects.requireNonNull(target, "target must not be null");
        if (this == target) {
            return true;
        }
        return switch (this) {
            case PENDING -> target == RUNNING || target == FAILED || target == CANCELLED;
            case RUNNING -> target == COMPLETED || target == INTERRUPTED
                    || target == FAILED || target == CANCELLED;
            case INTERRUPTED -> target == RUNNING || target == FAILED || target == CANCELLED;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
