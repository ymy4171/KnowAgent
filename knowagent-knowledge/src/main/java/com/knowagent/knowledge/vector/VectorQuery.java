package com.knowagent.knowledge.vector;

import com.knowagent.common.tenant.TenantId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A vector search request. The filter always contains {@code tenant_id} and
 * {@code knowledge_base_id}; {@code fileIds} optionally narrows the search to
 * specific files. All ids are typed UUIDs, so the adapter can validate them and
 * escape them into a controlled Milvus filter expression - arbitrary user
 * expressions are never concatenated (Rule 9).
 *
 * @param topK         maximum number of hits to return (1..16384)
 * @param minimumScore hits with a COSINE score below this value are dropped;
 *                     Milvus returns cosine similarity, larger is more similar
 */
public record VectorQuery(
        TenantId tenantId,
        UUID knowledgeBaseId,
        float[] embedding,
        int topK,
        double minimumScore,
        List<UUID> fileIds) {

    /** Milvus 2.5 default maximum topk is 16384. */
    public static final int MAX_TOP_K = 16384;

    public VectorQuery {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        embedding = embedding.clone();
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be in [1, " + MAX_TOP_K + "]");
        }
        if (minimumScore < -1.0 || minimumScore > 1.0) {
            throw new IllegalArgumentException("minimumScore must be in [-1, 1]");
        }
        fileIds = fileIds == null ? null : List.copyOf(fileIds);
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
