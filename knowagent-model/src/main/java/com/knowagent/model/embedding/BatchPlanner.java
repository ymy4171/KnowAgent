package com.knowagent.model.embedding;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Splits a text list into provider batches honoring three independent limits at once:
 * the maximum number of texts per batch, the estimated token total per batch and a
 * conservative estimate of the JSON request body size. Empty input is rejected up
 * front, and a single text that cannot fit even alone is rejected rather than silently
 * exceeding a provider limit.
 *
 * <p>The planner is deterministic and pure: the same input and limits always produce
 * the same batches, in the same order, and every batch except possibly the last is
 * packed as tightly as the limits allow.
 */
public final class BatchPlanner {

    /** Upper-bound overhead of the fixed JSON envelope excluding variable string values. */
    public static final int FIXED_REQUEST_OVERHEAD_BYTES = 256;
    /** Per-text JSON overhead: quotes, comma and the surrounding array slot. */
    private static final int PER_TEXT_OVERHEAD_BYTES = 8;

    private BatchPlanner() {
    }

    /** One planned provider batch: the texts plus the running token / body estimates. */
    public record Batch(List<String> texts, long estimatedTokens, long estimatedRequestBodyBytes) {

        public Batch {
            texts = List.copyOf(texts);
        }
    }

    /** The three provider limits plus the token estimator used to enforce them. */
    public record Limits(
            int maxTextsPerBatch,
            long maxTokensPerBatch,
            int maxRequestBodyBytes,
            long additionalRequestBodyBytes,
            TokenEstimator tokenEstimator) {

        public Limits(int maxTextsPerBatch, long maxTokensPerBatch, int maxRequestBodyBytes,
                      TokenEstimator tokenEstimator) {
            this(maxTextsPerBatch, maxTokensPerBatch, maxRequestBodyBytes, 0, tokenEstimator);
        }

        public Limits {
            Objects.requireNonNull(tokenEstimator, "tokenEstimator must not be null");
            if (maxTextsPerBatch < 1) {
                throw new IllegalArgumentException("maxTextsPerBatch must be >= 1");
            }
            if (maxTokensPerBatch < 1) {
                throw new IllegalArgumentException("maxTokensPerBatch must be >= 1");
            }
            if (maxRequestBodyBytes < 1) {
                throw new IllegalArgumentException("maxRequestBodyBytes must be >= 1");
            }
            if (additionalRequestBodyBytes < 0) {
                throw new IllegalArgumentException("additionalRequestBodyBytes must not be negative");
            }
        }

        long baseRequestBodyBytes() {
            return FIXED_REQUEST_OVERHEAD_BYTES + additionalRequestBodyBytes;
        }
    }

    /** Counts the estimated tokens of a single text. */
    public interface TokenEstimator {

        long estimateTokens(String text);
    }

    /**
     * Plans {@code texts} into batches. Rejects an empty or blank input and any single
     * text whose estimated tokens or request body size cannot fit a batch on its own.
     */
    public static List<Batch> plan(List<String> texts, Limits limits) {
        Objects.requireNonNull(limits, "limits must not be null");
        if (texts == null || texts.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Cannot embed an empty text list.");
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Cannot embed blank or null text.");
            }
        }

        List<Batch> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        long currentTokens = 0;
        long currentBytes = limits.baseRequestBodyBytes();
        for (String text : texts) {
            long tokens = limits.tokenEstimator().estimateTokens(text);
            long textBytes = estimateTextBytes(text);
            if (tokens > limits.maxTokensPerBatch()
                    || textBytes + limits.baseRequestBodyBytes() > limits.maxRequestBodyBytes()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "A single input text exceeds the maximum tokens or request body size allowed per batch.");
            }
            boolean fits = !current.isEmpty()
                    && current.size() + 1 <= limits.maxTextsPerBatch()
                    && currentTokens + tokens <= limits.maxTokensPerBatch()
                    && currentBytes + textBytes <= limits.maxRequestBodyBytes();
            if (!current.isEmpty() && !fits) {
                batches.add(new Batch(List.copyOf(current), currentTokens, currentBytes));
                current = new ArrayList<>();
                currentTokens = 0;
                currentBytes = limits.baseRequestBodyBytes();
            }
            current.add(text);
            currentTokens += tokens;
            currentBytes += textBytes;
        }
        if (!current.isEmpty()) {
            batches.add(new Batch(List.copyOf(current), currentTokens, currentBytes));
        }
        return batches;
    }

    private static long estimateTextBytes(String text) {
        return PER_TEXT_OVERHEAD_BYTES + estimateJsonStringBytes(text);
    }

    /**
     * Returns a conservative UTF-8 byte count for a JSON string value, excluding the
     * surrounding quotes. JSON control characters require a six-byte {@code \\uXXXX}
     * escape, so a flat four-bytes-per-code-unit estimate is not a safe upper bound.
     */
    public static long estimateJsonStringBytes(String value) {
        Objects.requireNonNull(value, "value must not be null");
        long bytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            char current = value.charAt(offset);
            if (current <= 0x1F) {
                bytes += 6;
                offset++;
            } else if (current == '"' || current == '\\') {
                bytes += 2;
                offset++;
            } else if (current <= 0x7F) {
                bytes++;
                offset++;
            } else if (current <= 0x7FF) {
                bytes += 2;
                offset++;
            } else if (Character.isHighSurrogate(current)
                    && offset + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(offset + 1))) {
                bytes += 4;
                offset += 2;
            } else if (Character.isSurrogate(current)) {
                // Invalid standalone surrogates are conservatively charged as an escape.
                bytes += 6;
                offset++;
            } else {
                bytes += 3;
                offset++;
            }
        }
        return bytes;
    }
}
