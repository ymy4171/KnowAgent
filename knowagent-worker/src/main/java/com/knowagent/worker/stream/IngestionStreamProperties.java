package com.knowagent.worker.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "knowagent.worker.stream")
public record IngestionStreamProperties(
        @DefaultValue("knowagent:knowledge-file-ingestion") String key,
        @DefaultValue("knowledge-file-ingestion") String group,
        @DefaultValue("worker-local") String consumer,
        @DefaultValue("20") int batchSize,
        @DefaultValue("1s") Duration pollTimeout,
        @DefaultValue("30s") Duration reclaimIdle,
        @DefaultValue("5m") Duration taskLease,
        @DefaultValue("30s") Duration outboxLease,
        @DefaultValue("true") boolean publisherEnabled,
        @DefaultValue("true") boolean consumerEnabled) {

    public IngestionStreamProperties {
        requireText(key, "key");
        requireText(group, "group");
        requireText(consumer, "consumer");
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be in [1, 1000]");
        }
        requirePositive(pollTimeout, "pollTimeout");
        requirePositive(reclaimIdle, "reclaimIdle");
        requirePositive(taskLease, "taskLease");
        requirePositive(outboxLease, "outboxLease");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must contain 1..128 characters");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
