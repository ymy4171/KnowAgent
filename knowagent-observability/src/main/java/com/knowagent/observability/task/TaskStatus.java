package com.knowagent.observability.task;

/**
 * Task lifecycle (mirrors the PostgreSQL CHECK on {@code tasks.status}). The
 * allowed transitions are centralised here so application code can validate a
 * move before it reaches the optimistic-lock guard; the store still enforces the
 * same move with a status + version conditional update in the database.
 */
public enum TaskStatus {
    PENDING(false),
    RUNNING(false),
    SUCCEEDED(true),
    FAILED(true),
    CANCELLED(true);

    private final boolean terminal;

    TaskStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /**
     * Whether {@code target} is a legal successor of this status. A FAILED task may
     * return to PENDING for a retry; everything else moves forward and terminal
     * states never transition.
     */
    public boolean canTransitionTo(TaskStatus target) {
        if (target == null) {
            return false;
        }
        if (this == PENDING) {
            return target == RUNNING || target == FAILED || target == CANCELLED;
        }
        if (this == RUNNING) {
            return target == SUCCEEDED || target == FAILED || target == PENDING || target == CANCELLED;
        }
        return false;
    }
}
