package com.knowagent.worker.stream;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.service.KnowledgeFileSubmissionService;
import com.knowagent.observability.application.service.OutboxPublisherService;
import com.knowagent.observability.outbox.OutboxEvent;
import com.knowagent.observability.outbox.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisOutboxPublisherTest {

    private OutboxPublisherService outbox;
    private StringRedisTemplate redis;
    private StreamOperations<String, Object, Object> stream;
    private RedisOutboxPublisher publisher;
    private OutboxEvent claimed;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        outbox = mock(OutboxPublisherService.class);
        redis = mock(StringRedisTemplate.class);
        stream = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn((StreamOperations) stream);
        publisher = new RedisOutboxPublisher(outbox, redis,
                new IngestionEventCodec(new com.fasterxml.jackson.databind.ObjectMapper()), properties());
        UUID fileId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        claimed = new OutboxEvent(UUID.randomUUID(), TenantId.of(UUID.randomUUID()),
                        KnowledgeFileSubmissionService.AGGREGATE_TYPE, fileId.toString(),
                        KnowledgeFileSubmissionService.EVENT_TYPE,
                        JsonNodeFactory.instance.objectNode().put("file_id", fileId.toString()),
                        JsonNodeFactory.instance.objectNode(), OutboxStatus.PENDING, 0, 5,
                        createdAt, null, null, null, null, 0, createdAt)
                .claimed("publisher-a", Instant.now(), Duration.ofSeconds(30));
    }

    @Test
    void marksPostgresPublishedOnlyAfterRedisAcceptedTheRecord() {
        when(stream.add(any())).thenReturn(RecordId.of("1-0"));

        publisher.publishOne(claimed);

        InOrder order = inOrder(stream, outbox);
        order.verify(stream).add(any());
        order.verify(outbox).publish(claimed);
        verify(outbox, never()).fail(any(), any());
    }

    @Test
    void redisWrittenPgNotMarkedCrashWindowDoesNotRewriteEventAsFailed() {
        when(stream.add(any())).thenReturn(RecordId.of("2-0"));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.CONFLICT, "lost lease"))
                .when(outbox).publish(claimed);

        publisher.publishOne(claimed);

        verify(outbox, never()).fail(any(), any());
    }

    @Test
    void redisFailureRecordsV9BackoffThroughOutboxService() {
        when(stream.add(any())).thenThrow(new IllegalStateException("redis unavailable"));

        publisher.publishOne(claimed);

        verify(outbox).fail(claimed, "Redis Stream publish failed.");
        verify(outbox, never()).publish(any());
    }

    private static IngestionStreamProperties properties() {
        return new IngestionStreamProperties("ingestion", "group", "worker-a", 20,
                Duration.ofMillis(10), Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(30), true, true);
    }
}
