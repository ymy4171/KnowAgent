package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.vector.VectorChunk;
import com.knowagent.knowledge.vector.VectorHit;
import com.knowagent.knowledge.vector.VectorQuery;
import com.knowagent.knowledge.vector.VectorStoreGateway;
import com.google.gson.JsonObject;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link VectorStoreGateway} backed by the Milvus Java SDK V2 API.
 *
 * <p>Every write/search/delete is scoped by {@code tenant_id} + {@code
 * knowledge_base_id} (+ {@code file_id} when present) through the controlled
 * {@link MilvusFilterBuilder}; entity ids equal PostgreSQL chunk UUIDs; search
 * returns only id/score/scalars; deleting an absent file is an idempotent success.
 * All calls run through {@link MilvusCallExecutor} with per-operation timeouts and
 * SDK failures are mapped to stable {@link VectorStoreException} codes. Milvus SDK
 * types never leave this infrastructure package.
 */
public final class MilvusVectorStoreAdapter implements VectorStoreGateway {

    static final String VECTOR_FIELD = "embedding";

    private final MilvusClientAccess client;
    private final MilvusVectorProperties properties;
    private final MilvusCallExecutor executor;
    private final VectorMetrics metrics;
    private final int dimension;

    public MilvusVectorStoreAdapter(MilvusClientAccess client, MilvusVectorProperties properties,
                                    MilvusCallExecutor executor, VectorMetrics metrics) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.dimension = properties.dimension();
    }

    @Override
    public void upsert(List<VectorChunk> chunks) {
        List<JsonObject> rows = MilvusVectorEntityMapper.toRows(chunks, dimension);
        UpsertResp response = executor.call(properties.collectionName(), "upsert",
                properties.writeTimeout(), () -> client.upsert(properties.collectionName(), rows));
        if (response.getUpsertCnt() != rows.size()) {
            throw new VectorStoreException(ErrorCode.VECTOR_BAD_RESPONSE,
                    "The vector store acknowledged a different number of upserted rows than submitted.");
        }
        metrics.recordEntities(properties.collectionName(), "upsert", rows.size());
    }

    @Override
    public List<VectorHit> search(VectorQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        float[] vector = query.embedding();
        requireVectorMatchesCollection(vector, "Query");
        String filter = MilvusFilterBuilder.withFileIds(query.tenantId(), query.knowledgeBaseId(), query.fileIds());
        SearchReq request = SearchReq.builder()
                .collectionName(properties.collectionName())
                .data(List.of(new FloatVec(vector)))
                .annsField(VECTOR_FIELD)
                .metricType(IndexParam.MetricType.COSINE)
                .topK(query.topK())
                .filter(filter)
                .outputFields(List.of(MilvusVectorEntityMapper.FIELD_FILE_ID,
                        MilvusVectorEntityMapper.FIELD_CHUNK_ID))
                .searchParams(MilvusIndexParams.searchParams(properties))
                // Strong consistency: a search issued right after an index write (the
                // worker's upsert) must see that write - "read your writes" for RAG.
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build();
        SearchResp response = executor.call(properties.collectionName(), "search",
                properties.searchTimeout(), () -> client.search(request));
        List<List<SearchResp.SearchResult>> perVector = response.getSearchResults();
        if (perVector == null || perVector.isEmpty()) {
            throw new VectorStoreException(ErrorCode.VECTOR_BAD_RESPONSE,
                    "The vector store returned no search result list.");
        }
        List<VectorHit> hits = MilvusSearchResultMapper.toHits(perVector.get(0));
        List<VectorHit> aboveThreshold = hits.stream()
                .filter(hit -> hit.score() >= query.minimumScore())
                .toList();
        metrics.recordEntities(properties.collectionName(), "search", aboveThreshold.size());
        return aboveThreshold;
    }

    @Override
    public void deleteByFile(UUID tenantId, UUID knowledgeBaseId, UUID fileId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        String filter = MilvusFilterBuilder.withFileIds(TenantId.of(tenantId), knowledgeBaseId, List.of(fileId));
        DeleteResp response = executor.call(properties.collectionName(), "delete",
                properties.deleteTimeout(), () -> client.delete(properties.collectionName(), filter));
        // A filter matching no rows (file absent or already deleted) is an idempotent
        // success: deleteCnt == 0 is a valid outcome, never an error.
        metrics.recordEntities(properties.collectionName(), "delete", response.getDeleteCnt());
    }

    private void requireVectorMatchesCollection(float[] vector, String subject) {
        if (vector.length != dimension) {
            throw new VectorStoreException(ErrorCode.VALIDATION_ERROR,
                    subject + " vector dimension " + vector.length
                            + " does not match the configured collection dimension " + dimension + ".");
        }
        for (float value : vector) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new VectorStoreException(ErrorCode.VALIDATION_ERROR,
                        subject + " vector values must all be finite.");
            }
        }
    }
}
