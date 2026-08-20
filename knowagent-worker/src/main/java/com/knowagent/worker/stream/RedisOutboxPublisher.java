package com.knowagent.worker.stream;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.observability.application.service.OutboxPublisherService;
import com.knowagent.observability.outbox.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Competing small-batch publisher; XADD succeeds before the guarded PG completion. */
@Component
public class RedisOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisOutboxPublisher.class);

    private final OutboxPublisherService outbox;
    private final StringRedisTemplate redis;
    private final IngestionEventCodec codec;
    private final IngestionStreamProperties properties;

    public RedisOutboxPublisher(OutboxPublisherService outbox,
                                StringRedisTemplate redis,
                                IngestionEventCodec codec,
                                IngestionStreamProperties properties) {
        this.outbox = outbox;
        this.redis = redis;
        this.codec = codec;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${knowagent.worker.stream.publisher-delay:1000}")
    public void publishReady() {
        if (!properties.publisherEnabled()) {
            return;
        }
        for (OutboxEvent event : outbox.claim(properties.batchSize(), publisherId(), properties.outboxLease())) {
            publishOne(event);
        }
    }

    void publishOne(OutboxEvent event) {
        try {
            String envelope = codec.encode(event);
            RecordId recordId = redis.opsForStream().add(
                    StreamRecords.newRecord().in(properties.key())
                            .ofStrings(Map.of(IngestionEventCodec.ENVELOPE_FIELD, envelope)));
            if (recordId == null) {
                throw new IllegalStateException("Redis did not return a stream record id");
            }
            try {
                outbox.publish(event);
                log.debug("Published outbox event {} as Redis record {}", event.id(), recordId.getValue());
            } catch (BusinessException conflict) {
                if (conflict.errorCode() != ErrorCode.CONFLICT) {
                    throw conflict;
                }
                // XADD already happened. A stale completion is intentionally left for
                // lease reclaim and Inbox deduplication; never rewrite it as failed.
                log.warn("Outbox event {} was written to Redis but its PG completion lost a race", event.id());
            }
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            try {
                outbox.fail(event, "Redis Stream publish failed.");
            } catch (RuntimeException stateFailure) {
                log.warn("Could not record publish failure for outbox event {} ({})",
                        event.id(), stateFailure.getClass().getSimpleName());
            }
            log.warn("Redis publish failed for outbox event {} ({})",
                    event.id(), failure.getClass().getSimpleName());
        }
    }

    private String publisherId() {
        return properties.consumer() + "-publisher";
    }
}
