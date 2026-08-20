package com.knowagent.model.infrastructure.embedding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

/**
 * Timeouts, retry budget and batching limits for embedding calls
 * ({@code knowagent.model.embedding.*}). All defaults are safe for an OpenAI-compatible
 * endpoint; a provider that needs larger per-batch bodies (for example a
 * self-hosted vLLM instance) can raise {@code maxRequestBodyBytes}.
 *
 * <p>{@code toString()} only renders the timeout and retry fields - it never renders
 * anything sensitive.
 */
@ConfigurationProperties(prefix = "knowagent.model.embedding")
public record EmbeddingProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("60s") Duration readTimeout,
        @DefaultValue("120s") Duration totalTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("500ms") Duration backoffInitial,
        @DefaultValue("2.0") double backoffMultiplier,
        @DefaultValue("10s") Duration backoffMax,
        @DefaultValue("100") int maxTextsPerBatch,
        @DefaultValue("8000") long maxTokensPerBatch,
        @DefaultValue("200000") int maxRequestBodyBytes,
        @DefaultValue("64") int maxClientCacheSize) {

    public EmbeddingProperties {
        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        Objects.requireNonNull(readTimeout, "readTimeout must not be null");
        Objects.requireNonNull(totalTimeout, "totalTimeout must not be null");
        Objects.requireNonNull(backoffInitial, "backoffInitial must not be null");
        Objects.requireNonNull(backoffMax, "backoffMax must not be null");
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("readTimeout must be positive");
        }
        if (totalTimeout.isNegative() || totalTimeout.isZero()) {
            throw new IllegalArgumentException("totalTimeout must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (backoffInitial.isNegative()) {
            throw new IllegalArgumentException("backoffInitial must not be negative");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
        }
        if (backoffMax.isNegative()) {
            throw new IllegalArgumentException("backoffMax must not be negative");
        }
        if (maxTextsPerBatch < 1) {
            throw new IllegalArgumentException("maxTextsPerBatch must be >= 1");
        }
        if (maxTokensPerBatch < 1) {
            throw new IllegalArgumentException("maxTokensPerBatch must be >= 1");
        }
        if (maxRequestBodyBytes < 1) {
            throw new IllegalArgumentException("maxRequestBodyBytes must be >= 1");
        }
        if (maxClientCacheSize < 1) {
            throw new IllegalArgumentException("maxClientCacheSize must be >= 1");
        }
    }

    @Override
    public String toString() {
        return "EmbeddingProperties[connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
                + ", totalTimeout=" + totalTimeout + ", maxAttempts=" + maxAttempts + "]";
    }
}
