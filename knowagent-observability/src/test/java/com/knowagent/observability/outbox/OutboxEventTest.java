package com.knowagent.observability.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.common.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxEventTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

    @Test
    void acceptsAValidPendingEvent() {
        OutboxEvent event = validEvent();
        assertThat(event.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.retryCount()).isZero();
        assertThat(event.version()).isZero();
    }

    @Test
    void rejectsBlankOrNullAggregateType() {
        assertThatThrownBy(() -> new OutboxEvent(UUID.randomUUID(), TENANT, " ", "kb-1", "kb.created",
                object(), object(), OutboxStatus.PENDING, 0, 3, NOW, null, null, null, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonObjectPayload() {
        assertThatThrownBy(() -> new OutboxEvent(UUID.randomUUID(), TENANT, "knowledge_base", "kb-1", "kb.created",
                OBJECT_MAPPER.createArrayNode(), object(), OutboxStatus.PENDING, 0, 3, NOW, null, null, null, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonObjectHeaders() {
        assertThatThrownBy(() -> new OutboxEvent(UUID.randomUUID(), TENANT, "knowledge_base", "kb-1", "kb.created",
                object(), OBJECT_MAPPER.getNodeFactory().textNode("x"), OutboxStatus.PENDING, 0, 3, NOW, null, null, null, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRetryCountOutOfRange() {
        // retryCount may equal maxRetries, but must never exceed it.
        assertThatThrownBy(() -> new OutboxEvent(UUID.randomUUID(), TENANT, "knowledge_base", "kb-1", "kb.created",
                object(), object(), OutboxStatus.PENDING, 4, 3, NOW, null, null, null, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        // maxRetries must be >= 1.
        assertThatThrownBy(() -> new OutboxEvent(UUID.randomUUID(), TENANT, "knowledge_base", "kb-1", "kb.created",
                object(), object(), OutboxStatus.PENDING, 0, 0, NOW, null, null, null, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHalfSetLock() {
        assertThatThrownBy(() -> new OutboxEvent(UUID.randomUUID(), TENANT, "knowledge_base", "kb-1", "kb.created",
                object(), object(), OutboxStatus.PROCESSING, 0, 3, NOW, "worker-1", null, null, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishedRequiresPublishedAt() {
        assertThatThrownBy(() -> new OutboxEvent(UUID.randomUUID(), TENANT, "knowledge_base", "kb-1", "kb.created",
                object(), object(), OutboxStatus.PUBLISHED, 0, 3, NOW, null, null, null, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeVersion() {
        assertThatThrownBy(() -> new OutboxEvent(UUID.randomUUID(), TENANT, "knowledge_base", "kb-1", "kb.created",
                object(), object(), OutboxStatus.PENDING, 0, 3, NOW, null, null, null, null, -1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimingMarksProcessingWithLeaseAndBumpsVersion() {
        OutboxEvent claimed = validEvent().claimed("worker-1", NOW, Duration.ofSeconds(30));

        assertThat(claimed.status()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(claimed.lockedBy()).isEqualTo("worker-1");
        assertThat(claimed.lockedUntil()).isEqualTo(NOW.plusSeconds(30));
        assertThat(claimed.version()).isEqualTo(1);
        // The claim does not touch the retry schedule or the payload.
        assertThat(claimed.retryCount()).isZero();
        assertThat(claimed.nextRetryAt()).isEqualTo(NOW);
        assertThat(claimed.payload()).isEqualTo(validEvent().payload());
    }

    @Test
    void publishingSetsPublishedAtClearsLockAndBumpsVersion() {
        OutboxEvent claimed = validEvent().claimed("worker-1", NOW, Duration.ofSeconds(30));
        OutboxEvent published = claimed.published(NOW.plusSeconds(2));

        assertThat(published.status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.publishedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(published.lockedBy()).isNull();
        assertThat(published.lockedUntil()).isNull();
        assertThat(published.version()).isEqualTo(2);
    }

    @Test
    void failureWithoutExhaustedBudgetReturnsToPendingWithBackoff() {
        OutboxEvent claimed = validEvent().claimed("worker-1", NOW, Duration.ofSeconds(30));
        RetryPolicy policy = new RetryPolicy(1000, 60000);

        OutboxEvent failed = claimed.failure("boom", NOW.plusSeconds(1), policy);

        assertThat(failed.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.retryCount()).isEqualTo(1);
        assertThat(failed.nextRetryAt()).isEqualTo(NOW.plusSeconds(1).plusSeconds(1)); // base * 2^0
        assertThat(failed.lastError()).isEqualTo("boom");
        assertThat(failed.lockedBy()).isNull();
        assertThat(failed.version()).isEqualTo(2);
    }

    @Test
    void failureExhaustingBudgetDeadLetters() {
        OutboxEvent nearBudget = validEvent(2, 3).claimed("worker-1", NOW, Duration.ofSeconds(30));

        OutboxEvent failed = nearBudget.failure("gave up", NOW.plusSeconds(1), new RetryPolicy(1000, 60000));

        assertThat(failed.status()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(failed.retryCount()).isEqualTo(3);
        // A dead letter has no backoff: it is simply not retried.
        assertThat(failed.nextRetryAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(failed.version()).isEqualTo(2);
    }

    @Test
    void backoffDoublesOnEachRetry() {
        RetryPolicy policy = new RetryPolicy(1000, 60000);
        OutboxEvent event = validEvent().claimed("worker-1", NOW, Duration.ofSeconds(30));

        OutboxEvent first = event.failure("e", NOW, policy);
        assertThat(first.nextRetryAt()).isEqualTo(NOW.plusSeconds(1));

        // The second failure lands at the first retry time and pushes it back by 2s.
        OutboxEvent second = first.claimed("worker-1", first.nextRetryAt(), Duration.ofSeconds(30))
                .failure("e", first.nextRetryAt(), policy);
        assertThat(second.nextRetryAt()).isEqualTo(first.nextRetryAt().plusSeconds(2));
    }

    private static OutboxEvent validEvent() {
        return validEvent(0, 3);
    }

    private static OutboxEvent validEvent(int retryCount, int maxRetries) {
        return new OutboxEvent(UUID.randomUUID(), TENANT, "knowledge_base", "kb-1", "kb.created",
                object(), object(), OutboxStatus.PENDING, retryCount, maxRetries, NOW,
                null, null, null, null, 0, NOW);
    }

    private static JsonNode object() {
        return OBJECT_MAPPER.createObjectNode().put("source", "unit-test");
    }
}
