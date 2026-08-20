package com.knowagent.knowledge.chunk;

/**
 * Indexing lifecycle of a stored chunk, matching the {@code index_status} CHECK constraint
 * on {@code knowledge_chunks} exactly (case-sensitive uppercase). Chunks are written as
 * {@link #PENDING}; later stages move them to {@link #INDEXING}, {@link #READY} or
 * {@link #FAILED}.
 */
public enum ChunkIndexStatus {
    PENDING,
    INDEXING,
    READY,
    FAILED
}
