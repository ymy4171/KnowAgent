package com.knowagent.knowledge.knowledgebase;

/**
 * Type-safe retrieval configuration stored as the {@code retrieval_config} JSONB
 * column on {@code knowledge_bases}. {@code topK} is the candidate count, {@code
 * scoreThreshold} the minimum similarity score in {@code [0,1]} (0.0 means "no
 * threshold"), and {@code rerankEnabled} whether a rerank pass should run before the
 * final answer. The constructor bounds the values so an invalid config can never be
 * persisted.
 */
public record RetrievalConfig(
        int topK,
        double scoreThreshold,
        boolean rerankEnabled
) {

    public static final int MIN_TOP_K = 1;
    public static final int MAX_TOP_K = 100;

    public RetrievalConfig {
        if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
            throw new IllegalArgumentException(
                    "topK must be between " + MIN_TOP_K + " and " + MAX_TOP_K);
        }
        if (!Double.isFinite(scoreThreshold) || scoreThreshold < 0.0 || scoreThreshold > 1.0) {
            throw new IllegalArgumentException("scoreThreshold must be within [0, 1]");
        }
    }

    /** The configuration used when a knowledge base does not specify one. */
    public static RetrievalConfig defaults() {
        return new RetrievalConfig(10, 0.0, false);
    }
}
