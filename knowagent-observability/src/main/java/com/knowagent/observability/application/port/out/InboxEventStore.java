package com.knowagent.observability.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.inbox.InboxEvent;

import java.util.UUID;

/**
 * Idempotent inbox receipts for a consumer group.
 *
 * <p>Recording a receipt relies on the unique constraint
 * {@code uq_inbox_events_consumer_event (consumer_name, event_id)}: a replay inserts
 * nothing and reports "already processed" instead of raising an error.
 */
public interface InboxEventStore {

    /**
     * Records one processed event. Returns {@code true} when this call created the
     * receipt (the event was new to this consumer), {@code false} when the receipt
     * already existed.
     */
    boolean recordProcessed(InboxEvent event);

    /** Whether this consumer has already processed the event. */
    boolean wasProcessed(TenantId tenantId, String consumerName, UUID eventId);
}
