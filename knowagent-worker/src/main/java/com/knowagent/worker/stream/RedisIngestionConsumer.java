package com.knowagent.worker.stream;

import com.knowagent.knowledge.application.service.KnowledgeFileIngestionCommand;
import com.knowagent.knowledge.application.service.KnowledgeFileIngestionOutcome;
import com.knowagent.knowledge.application.service.KnowledgeFileIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Manual-ACK consumer with XPENDING + XCLAIM crash recovery. */
@Component
public class RedisIngestionConsumer {

    private static final Logger log = LoggerFactory.getLogger(RedisIngestionConsumer.class);

    private final StringRedisTemplate redis;
    private final IngestionEventCodec codec;
    private final WorkerTenantScope tenantScope;
    private final KnowledgeFileIngestionService ingestion;
    private final IngestionStreamProperties properties;
    private volatile boolean groupReady;

    public RedisIngestionConsumer(StringRedisTemplate redis,
                                  IngestionEventCodec codec,
                                  WorkerTenantScope tenantScope,
                                  KnowledgeFileIngestionService ingestion,
                                  IngestionStreamProperties properties) {
        this.redis = redis;
        this.codec = codec;
        this.tenantScope = tenantScope;
        this.ingestion = ingestion;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${knowagent.worker.stream.poll-delay:250}")
    public void pollNew() {
        if (!properties.consumerEnabled()) {
            return;
        }
        try {
            ensureGroup();
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(properties.group(), properties.consumer()),
                    StreamReadOptions.empty().count(properties.batchSize()).block(properties.pollTimeout()),
                    StreamOffset.create(properties.key(), ReadOffset.lastConsumed()));
            process(records);
        } catch (DataAccessException failure) {
            groupReady = false;
            log.warn("Redis Stream read failed ({})", failure.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${knowagent.worker.stream.reclaim-delay:5000}")
    public void reclaimPending() {
        if (!properties.consumerEnabled()) {
            return;
        }
        try {
            ensureGroup();
            PendingMessages pending = redis.opsForStream().pending(
                    properties.key(), properties.group(), Range.unbounded(), properties.batchSize());
            List<RecordId> stale = new ArrayList<>();
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery().compareTo(properties.reclaimIdle()) >= 0) {
                    stale.add(message.getId());
                }
            }
            if (stale.isEmpty()) {
                return;
            }
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                    properties.key(), properties.group(), properties.consumer(), properties.reclaimIdle(),
                    stale.toArray(RecordId[]::new));
            process(claimed);
        } catch (DataAccessException failure) {
            groupReady = false;
            log.warn("Redis pending reclaim failed ({})", failure.getClass().getSimpleName());
        }
    }

    private void process(List<MapRecord<String, Object, Object>> records) {
        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            processOne(record);
        }
    }

    private void processOne(MapRecord<String, Object, Object> record) {
        try {
            String raw = envelope(record.getValue());
            IngestionEventEnvelope event = codec.decode(raw);
            KnowledgeFileIngestionOutcome outcome = tenantScope.call(event.tenantId(), () ->
                    ingestion.ingest(new KnowledgeFileIngestionCommand(
                                    event.tenantId(), event.eventId(), properties.group(),
                                    event.fileId(), event.payloadHash()),
                            properties.consumer(), properties.taskLease()));
            if (outcome.acknowledge()) {
                acknowledge(record);
            }
        } catch (InvalidEventEnvelopeException poison) {
            // Invalid schema/type/tenant is permanent and was rejected before a
            // TenantContext existed. ACK prevents an untrusted poison loop.
            acknowledge(record);
            log.warn("Rejected invalid Redis ingestion record {}", record.getId().getValue());
        } catch (RuntimeException failure) {
            // No ACK: the record remains pending and is eligible for reclaim.
            log.warn("Ingestion record {} remains pending ({})",
                    record.getId().getValue(), failure.getClass().getSimpleName());
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redis.opsForStream().acknowledge(properties.key(), properties.group(), record.getId());
    }

    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        try {
            redis.opsForStream().createGroup(properties.key(), ReadOffset.from("0-0"), properties.group());
        } catch (DataAccessException failure) {
            if (!containsBusyGroup(failure)) {
                throw failure;
            }
        }
        groupReady = true;
    }

    private static String envelope(Map<Object, Object> values) {
        Object value = values.get(IngestionEventCodec.ENVELOPE_FIELD);
        if (value == null) {
            throw new InvalidEventEnvelopeException("The Redis record has no event envelope.");
        }
        return value.toString();
    }

    private static boolean containsBusyGroup(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }
}
