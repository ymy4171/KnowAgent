package com.knowagent.observability.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.application.port.out.OutboxEventStore;
import com.knowagent.observability.outbox.OutboxEvent;
import com.knowagent.observability.outbox.OutboxStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class OutboxPublisherServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

    private final FakeOutboxEventStore store = new FakeOutboxEventStore();

    @Test
    void claimRejectsNonPositiveLimit() {
        OutboxPublisherService service = new OutboxPublisherService(store);

        BusinessException ex = catchThrowableOfType(() -> service.claim(0, "worker-1", Duration.ofSeconds(30)),
                BusinessException.class);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void claimForwardsWorkerAndLease() {
        OutboxPublisherService service = new OutboxPublisherService(store);
        OutboxEvent ready = validEvent();
        store.claimResult = List.of(ready);

        List<OutboxEvent> claimed = service.claim(5, "worker-1", Duration.ofSeconds(30));

        assertThat(claimed).containsExactly(ready);
        assertThat(store.lastLimit).isEqualTo(5);
        assertThat(store.lastWorkerId).isEqualTo("worker-1");
        assertThat(store.lastLease).isEqualTo(Duration.ofSeconds(30));
        assertThat(store.lastNow).isNotNull();
    }

    @Test
    void publishSucceedsWhenTheRaceIsWon() {
        OutboxPublisherService service = new OutboxPublisherService(store);
        store.markPublishedResult = 1;

        service.publish(validEvent());
    }

    @Test
    void publishConflictWhenAnotherPublisherAlreadyWon() {
        OutboxPublisherService service = new OutboxPublisherService(store);
        store.markPublishedResult = 0;

        BusinessException ex = catchThrowableOfType(() -> service.publish(validEvent()), BusinessException.class);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void failSanitizesTheErrorBeforeStoring() {
        OutboxPublisherService service = new OutboxPublisherService(store);
        OutboxEvent processing = validEvent().claimed("worker-1", NOW, Duration.ofSeconds(30));
        store.markFailedResult = 1;
        String rawError = "boom\000 api_key=sk-xyz\n" + "d".repeat(5000);

        service.fail(processing, rawError);

        assertThat(store.markedCurrent).isSameAs(processing);
        assertThat(store.markedTarget.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(store.markedTarget.retryCount()).isEqualTo(1);
        assertThat(store.markedTarget.lastError()).doesNotContain("\000");
        // The credential must be redacted away, never preserved in last_error.
        assertThat(store.markedTarget.lastError()).doesNotContain("sk-xyz");
        assertThat(store.markedTarget.lastError()).contains("api_key=<redacted>");
        assertThat(store.markedTarget.lastError()).hasSize(ErrorMessageSanitizer.DEFAULT_MAX_LENGTH);
    }

    @Test
    void failRedactsBearerTokensBeforeStoring() {
        OutboxPublisherService service = new OutboxPublisherService(store);
        OutboxEvent processing = validEvent().claimed("worker-1", NOW, Duration.ofSeconds(30));
        store.markFailedResult = 1;
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.sig";
        String rawError = "Authorization: Bearer " + jwt;

        service.fail(processing, rawError);

        assertThat(store.markedTarget.lastError())
                .doesNotContain(jwt)
                .contains("Authorization: Bearer <redacted>");
    }

    @Test
    void failDeadLettersWhenRetryBudgetIsExhausted() {
        OutboxPublisherService service = new OutboxPublisherService(store);
        OutboxEvent processing = validEvent(2, 3).claimed("worker-1", NOW, Duration.ofSeconds(30));
        store.markFailedResult = 1;

        service.fail(processing, "gave up");

        assertThat(store.markedTarget.status()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(store.markedTarget.retryCount()).isEqualTo(3);
    }

    @Test
    void failConflictWhenAnotherPublisherAlreadyWon() {
        OutboxPublisherService service = new OutboxPublisherService(store);
        OutboxEvent processing = validEvent().claimed("worker-1", NOW, Duration.ofSeconds(30));
        store.markFailedResult = 0;

        BusinessException ex = catchThrowableOfType(() -> service.fail(processing, "boom"), BusinessException.class);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT);
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

    private static final class FakeOutboxEventStore implements OutboxEventStore {
        List<OutboxEvent> claimResult = List.of();
        int markPublishedResult;
        int markFailedResult;
        int lastLimit;
        String lastWorkerId;
        Duration lastLease;
        Instant lastNow;
        OutboxEvent markedCurrent;
        OutboxEvent markedTarget;

        @Override
        public void append(OutboxEvent event) {
            throw new UnsupportedOperationException("not used by the publisher service");
        }

        @Override
        public Optional<OutboxEvent> findById(TenantId tenantId, UUID eventId) {
            throw new UnsupportedOperationException("not used by the publisher service");
        }

        @Override
        public List<OutboxEvent> claimReady(int limit, Instant now, String workerId, Duration lease) {
            lastLimit = limit;
            lastNow = now;
            lastWorkerId = workerId;
            lastLease = lease;
            return claimResult;
        }

        @Override
        public int markPublished(OutboxEvent event) {
            return markPublishedResult;
        }

        @Override
        public int markFailed(OutboxEvent current, OutboxEvent target) {
            markedCurrent = current;
            markedTarget = target;
            return markFailedResult;
        }
    }
}
