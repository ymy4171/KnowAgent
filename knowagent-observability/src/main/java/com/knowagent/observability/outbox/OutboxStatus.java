package com.knowagent.observability.outbox;

/**
 * Lifecycle of a transactional outbox event (mirrors the PostgreSQL CHECK on
 * {@code outbox_events.status}).
 *
 * <ul>
 *   <li>{@code PENDING} - appended in the business transaction, waiting for a
 *       competing publisher.</li>
 *   <li>{@code PROCESSING} - claimed by a publisher; guarded by a lease
 *       ({@code locked_by}/{@code locked_until}) so a crashed publisher can be
 *       reclaimed after the lease expires.</li>
 *   <li>{@code PUBLISHED} - successfully delivered to the broker.</li>
 *   <li>{@code DEAD_LETTER} - exhausted all retries.</li>
 * </ul>
 */
public enum OutboxStatus {

    PENDING(false),
    PROCESSING(false),
    PUBLISHED(true),
    DEAD_LETTER(true);

    private final boolean terminal;

    OutboxStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
