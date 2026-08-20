package com.knowagent.observability.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.outbox.OutboxEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional outbox events.
 *
 * <p>Append happens inside the business transaction. Competing publishers claim
 * ready events with {@code FOR UPDATE SKIP LOCKED} (cross-tenant by design, see the
 * mapper contract), then complete or fail each one with a status + version guard so
 * two publishers can never both mark the same event.
 */
public interface OutboxEventStore {

    /** Appends a PENDING event as part of the caller's transaction. */
    void append(OutboxEvent event);

    /** Reads one event strictly inside the tenant (empty for another tenant). */
    Optional<OutboxEvent> findById(TenantId tenantId, UUID eventId);

    /**
     * Claims up to {@code limit} ready events (PENDING, or PROCESSING with an
     * expired lease), ordered by {@code next_retry_at} then {@code created_at}, and
     * marks them PROCESSING with the given lease. Returns the claimed events at
     * their post-claim version; the returned set never overlaps between concurrent
     * callers.
     */
    List<OutboxEvent> claimReady(int limit, Instant now, String workerId, Duration lease);

    /**
     * Marks a PROCESSING event published. Returns 1 on success, 0 when the version
     * or status no longer matches (lost a race).
     */
    int markPublished(OutboxEvent event);

    /**
     * Records a failed delivery attempt: {@code target} already carries the next
     * status (PENDING for a retry or DEAD_LETTER when exhausted), incremented retry
     * count and backoff. Returns 1 on success, 0 when the version or status no
     * longer matches.
     */
    int markFailed(OutboxEvent current, OutboxEvent target);
}
