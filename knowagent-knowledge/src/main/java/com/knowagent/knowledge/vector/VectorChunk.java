package com.knowagent.knowledge.vector;

import com.knowagent.common.tenant.TenantId;

import java.util.Objects;
import java.util.UUID;

/**
 * A chunk to write into the vector store. Only retrieval-relevant data is carried:
 * the tenant/knowledge-base/file/chunk identity, the embedding model spec and the
 * embedding itself. PostgreSQL stays the source of truth for the chunk body and
 * metadata - the Milvus adapter never persists {@code content}.
 *
 * <p>The Milvus primary key ({@code id}, VARCHAR, autoID=false) must equal
 * {@code chunkId} so PostgreSQL chunk UUIDs and Milvus entity ids match one-to-one.
 *
 * @param embeddingModelSpec a stable, non-sensitive description of the embedding model
 *                           used (for example {@code "openai-compatible/text-embedding-3-small"}).
 *                           It is stored as a scalar so the application can detect vectors
 *                           produced by a different model after a configuration change.
 */
public record VectorChunk(
        TenantId tenantId,
        UUID knowledgeBaseId,
        UUID fileId,
        UUID chunkId,
        String content,
        float[] embedding,
        String embeddingModelSpec) {

    /** Upper bound kept in sync with the Milvus VARCHAR max length for the field. */
    public static final int MAX_MODEL_SPEC_LENGTH = 255;

    public VectorChunk {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(embeddingModelSpec, "embeddingModelSpec must not be null");
        if (embeddingModelSpec.isBlank()) {
            throw new IllegalArgumentException("embeddingModelSpec must not be blank");
        }
        if (embeddingModelSpec.length() > MAX_MODEL_SPEC_LENGTH) {
            throw new IllegalArgumentException("embeddingModelSpec must not exceed " + MAX_MODEL_SPEC_LENGTH + " characters");
        }
        embedding = embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
