package com.knowagent.knowledge.chunk;

import java.util.Objects;

/**
 * Deterministic chunking policy stored as the {@code chunk_policy} JSONB column on
 * {@code knowledge_bases}. {@code maxTokens} is the chunk budget (the prompt's
 * "chunkSize"), {@code overlapTokens} the shared overlap between neighbouring chunks.
 * The constructor enforces the invariant {@code overlapTokens >= 0 &&
 * overlapTokens < maxTokens}, so an invalid policy can never be persisted.
 */
public record ChunkPolicy(
        Strategy strategy,
        int maxTokens,
        int overlapTokens
) {

    public ChunkPolicy {
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("chunk size must be > 0");
        }
        if (overlapTokens < 0) {
            throw new IllegalArgumentException("overlap must be >= 0");
        }
        if (overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("overlap must be < chunk size");
        }
    }

    /** The policy used when a knowledge base does not specify one. */
    public static ChunkPolicy defaults() {
        return new ChunkPolicy(Strategy.RECURSIVE, 800, 100);
    }

    public enum Strategy {
        RECURSIVE,
        MARKDOWN_HEADING,
        TOKEN_WINDOW
    }
}

