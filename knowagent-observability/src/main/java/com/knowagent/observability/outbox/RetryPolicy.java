package com.knowagent.observability.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic exponential backoff for outbox retries.
 *
 * <p>The delay for the {@code n}-th retry (1-based) is
 * {@code min(maxDelayMillis, baseDelayMillis * 2^(n-1))}. It is a pure function of
 * the attempt number - no jitter - so tests can assert the exact
 * {@code next_retry_at} produced by a failure.
 */
public record RetryPolicy(long baseDelayMillis, long maxDelayMillis) {

    /** 1 second base, 5 minute cap - the production default. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(Duration.ofSeconds(1).toMillis(),
            Duration.ofMinutes(5).toMillis());

    public RetryPolicy {
        if (baseDelayMillis <= 0) {
            throw new IllegalArgumentException("baseDelayMillis must be > 0");
        }
        if (maxDelayMillis < baseDelayMillis) {
            throw new IllegalArgumentException("maxDelayMillis must be >= baseDelayMillis");
        }
    }

    /**
     * Returns the instant the given attempt may run again, doubling the delay for
     * each earlier attempt up to {@link #maxDelayMillis()}.
     *
     * @param attempt 1-based retry number; 1 is the first retry after the initial
     *                failure.
     */
    public Instant nextRetryAt(Instant now, int attempt) {
        Objects.requireNonNull(now, "now must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        long delay = baseDelayMillis;
        for (int i = 1; i < attempt && delay < maxDelayMillis; i++) {
            delay = Math.min(maxDelayMillis, Math.multiplyExact(delay, 2));
        }
        return now.plusMillis(delay);
    }
}
