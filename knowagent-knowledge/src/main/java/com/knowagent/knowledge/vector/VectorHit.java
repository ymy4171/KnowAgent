package com.knowagent.knowledge.vector;

import java.util.UUID;

/**
 * A vector-store hit. The Milvus adapter only fills {@code chunkId}, {@code fileId}
 * and {@code score} (Milvus stores no chunk body); {@code content} is hydrated by
 * the application layer afterwards from PostgreSQL using the tenant-scoped chunk ids,
 * never from the vector store itself.
 */
public record VectorHit(
        UUID chunkId,
        UUID fileId,
        String content,
        double score) {
}
