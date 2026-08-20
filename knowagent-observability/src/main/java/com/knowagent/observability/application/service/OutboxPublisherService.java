package com.knowagent.observability.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.observability.application.port.out.OutboxEventStore;
import com.knowagent.observability.outbox.OutboxEvent;
import com.knowagent.observability.outbox.RetryPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The operations a competing outbox publisher runs, without the broker itself:
 * claim ready events, then complete or fail each one. The actual publish step (a
 * Redis Streams XADD) is deliberately outside this module - it will be supplied by
 * {@code knowagent-worker}, which calls {@link #claim} and then either
 * {@link #publish} or {@link #fail}.
 *
 * <p>Claim and complete/fail are separate transactions. Claiming releases the
 * {@code FOR UPDATE SKIP LOCKED} row locks when it commits; the status + version
 * guard on the later transition is what stops a second publisher from completing an
 * event that a crashed one left PROCESSING within its lease.
 */
@Service
public class OutboxPublisherService {

    private final OutboxEventStore outboxEvents;
    private final RetryPolicy retryPolicy;

    @Autowired
    public OutboxPublisherService(OutboxEventStore outboxEvents) {
        this(outboxEvents, RetryPolicy.DEFAULT);
    }

    public OutboxPublisherService(OutboxEventStore outboxEvents, RetryPolicy retryPolicy) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
    }

    /** Claims up to {@code limit} ready events under the given worker id and lease. */
    @Transactional
    public List<OutboxEvent> claim(int limit, String workerId, Duration lease) {
        if (limit < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit must be >= 1");
        }
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(lease, "lease must not be null");
        return outboxEvents.claimReady(limit, Instant.now(), workerId, lease);
    }

    /** Marks the claimed event published; a lost version/status race is a conflict. */
    @Transactional
    public void publish(OutboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (outboxEvents.markPublished(event) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "The outbox event was modified concurrently; another publisher already claimed it.");
        }
    }

    /**
     * Records a failed delivery: computes the retry / dead-letter transition and
     * applies it. The error text is sanitized inside
     * {@link OutboxEvent#failure(String, Instant, RetryPolicy)} - the single shared
     * sanitization boundary for Task and Outbox errors - so the store never sees a
     * raw message. A lost version/status race is a conflict.
     */
    @Transactional
    public void fail(OutboxEvent event, String rawError) {
        Objects.requireNonNull(event, "event must not be null");
        OutboxEvent target = event.failure(rawError, Instant.now(), retryPolicy);
        if (outboxEvents.markFailed(event, target) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "The outbox event was modified concurrently; another publisher already claimed it.");
        }
    }
}
