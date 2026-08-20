package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.vector.VectorChunk;
import com.knowagent.knowledge.vector.VectorHit;
import com.knowagent.knowledge.vector.VectorQuery;
import com.knowagent.knowledge.vector.VectorStoreGateway;

import java.util.List;
import java.util.UUID;

/**
 * Fail-fast {@link VectorStoreGateway} used when no
 * {@code knowagent.vector.milvus.uri} is configured. It exists so the application
 * context boots without Milvus (every existing integration test does), while any
 * actual vector-store operation fails loudly with a stable
 * {@link VectorStoreException} instead of silently succeeding.
 */
public final class UnavailableVectorStoreGateway implements VectorStoreGateway {

    @Override
    public void upsert(List<VectorChunk> chunks) {
        throw unavailable();
    }

    @Override
    public List<VectorHit> search(VectorQuery query) {
        throw unavailable();
    }

    @Override
    public void deleteByFile(UUID tenantId, UUID knowledgeBaseId, UUID fileId) {
        throw unavailable();
    }

    private static VectorStoreException unavailable() {
        return new VectorStoreException(ErrorCode.VECTOR_UNAVAILABLE,
                "The vector store is not configured.");
    }
}
