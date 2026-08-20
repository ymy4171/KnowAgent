package com.knowagent.worker.stream;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.service.KnowledgeFileIngestionOutcome;
import com.knowagent.knowledge.application.service.KnowledgeFileIngestionService;
import com.knowagent.security.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisIngestionConsumerTest {

    private StringRedisTemplate redis;
    private StreamOperations<String, Object, Object> stream;
    private IngestionEventCodec codec;
    private KnowledgeFileIngestionService ingestion;
    private RedisIngestionConsumer consumer;
    private IngestionEventEnvelope envelope;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        stream = mock(StreamOperations.class);
        codec = mock(IngestionEventCodec.class);
        ingestion = mock(KnowledgeFileIngestionService.class);
        when(redis.opsForStream()).thenReturn((StreamOperations) stream);
        when(stream.createGroup(anyString(), any(), anyString())).thenReturn("OK");
        UUID fileId = UUID.randomUUID();
        envelope = new IngestionEventEnvelope(UUID.randomUUID(),
                "knowledge-file.ingestion.requested.v1", TenantId.of(UUID.randomUUID()), fileId,
                Instant.now(), 1, JsonNodeFactory.instance.objectNode().put("file_id", fileId.toString()),
                "a".repeat(64));
        when(codec.decode("event-json")).thenReturn(envelope);
        when(ingestion.ingest(any(), anyString(), any())).thenAnswer(invocation -> {
            assertThat(TenantContext.requireTenantId()).isEqualTo(envelope.tenantId());
            return KnowledgeFileIngestionOutcome.COMPLETED;
        });
        consumer = new RedisIngestionConsumer(redis, codec, new WorkerTenantScope(), ingestion, properties());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void successfulBusinessCompletionIsAcknowledgedAndContextIsCleared() {
        MapRecord record = record("1-0");
        when(stream.read(any(Consumer.class), any(), any())).thenReturn(List.of(record));

        consumer.pollNew();

        verify(stream).acknowledge("ingestion", "group", RecordId.of("1-0"));
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void invalidEnvelopeIsAcknowledgedWithoutInstallingTenantContext() {
        MapRecord record = record("2-0");
        when(stream.read(any(Consumer.class), any(), any())).thenReturn(List.of(record));
        when(codec.decode("event-json")).thenThrow(new InvalidEventEnvelopeException("invalid"));

        consumer.pollNew();

        verify(stream).acknowledge("ingestion", "group", RecordId.of("2-0"));
        verify(ingestion, never()).ingest(any(), anyString(), any());
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void stalePendingRecordIsClaimedByTheSurvivingConsumerThenAcknowledged() {
        RecordId id = RecordId.of("3-0");
        PendingMessage stale = new PendingMessage(id, Consumer.from("group", "dead-worker"),
                Duration.ofMinutes(2), 2);
        when(stream.pending(eq("ingestion"), eq("group"), any(), anyLong()))
                .thenReturn(new PendingMessages("group", List.of(stale)));
        MapRecord claimed = record("3-0");
        when(stream.claim(eq("ingestion"), eq("group"), eq("worker-a"),
                eq(Duration.ofSeconds(30)), any(RecordId[].class))).thenReturn(List.of(claimed));

        consumer.reclaimPending();

        verify(stream).claim(eq("ingestion"), eq("group"), eq("worker-a"),
                eq(Duration.ofSeconds(30)), any(RecordId[].class));
        verify(stream).acknowledge("ingestion", "group", id);
        assertThat(TenantContext.isSet()).isFalse();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static MapRecord<String, Object, Object> record(String id) {
        return (MapRecord) StreamRecords.newRecord()
                .in("ingestion")
                .withId(RecordId.of(id))
                .ofMap(Map.of(IngestionEventCodec.ENVELOPE_FIELD, "event-json"));
    }

    private static IngestionStreamProperties properties() {
        return new IngestionStreamProperties("ingestion", "group", "worker-a", 20,
                Duration.ofMillis(1), Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(30), true, true);
    }
}
