package com.knowagent.model.embedding;

import java.util.List;
import java.util.Objects;

/**
 * Response value object for {@link EmbeddingGateway#embed}. {@code vectors} holds
 * exactly one vector per input text, in input order; {@code dimensions} is the
 * validated, consistent dimension shared by every vector. No provider or Spring AI type
 * is exposed. Vectors are plain {@code float} arrays - never logged, only handed to the
 * caller (for example the Milvus writer).
 */
public record EmbeddingResult(
        List<float[]> vectors,
        int dimensions,
        String model,
        int batchCount,
        long estimatedTokens) {

    public EmbeddingResult {
        Objects.requireNonNull(vectors, "vectors must not be null");
        Objects.requireNonNull(model, "model must not be null");
        vectors = List.copyOf(vectors);
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be > 0");
        }
        if (batchCount < 0) {
            throw new IllegalArgumentException("batchCount must not be negative");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must not be negative");
        }
    }

    @Override
    public String toString() {
        return "EmbeddingResult[vectorCount=" + vectors.size() + ", dimensions=" + dimensions
                + ", model=" + model + ", batchCount=" + batchCount + ", estimatedTokens=" + estimatedTokens + "]";
    }
}
