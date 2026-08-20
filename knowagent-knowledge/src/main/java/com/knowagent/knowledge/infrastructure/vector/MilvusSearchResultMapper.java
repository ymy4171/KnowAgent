package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.vector.VectorHit;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps Milvus {@link SearchResp.SearchResult} objects to {@link VectorHit} domain
 * objects. Only the entity id, the file id scalar and the score cross the boundary;
 * {@code content} is intentionally left null (PostgreSQL re-hydrates it afterwards).
 * A response that is missing the id, file id or score, or whose id is not a UUID,
 * is a malformed server response and fails closed with VECTOR_BAD_RESPONSE.
 */
final class MilvusSearchResultMapper {

    private MilvusSearchResultMapper() {
    }

    /**
     * @param results the hits for a single query vector, in Milvus score order
     *                (COSINE similarity, descending)
     */
    static List<VectorHit> toHits(List<SearchResp.SearchResult> results) {
        return results.stream().map(MilvusSearchResultMapper::toHit).toList();
    }

    private static VectorHit toHit(SearchResp.SearchResult result) {
        UUID chunkId = parseChunkId(result.getId());
        double score = parseScore(result.getScore());
        UUID fileId = parseFileId(result.getEntity());
        return new VectorHit(chunkId, fileId, null, score);
    }

    private static UUID parseChunkId(Object id) {
        if (!(id instanceof String raw)) {
            throw new VectorStoreException(ErrorCode.VECTOR_BAD_RESPONSE,
                    "The vector search response is missing a chunk id.");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw new VectorStoreException(ErrorCode.VECTOR_BAD_RESPONSE,
                    "The vector search response returned an illegal chunk id.");
        }
    }

    private static double parseScore(Float score) {
        if (score == null) {
            throw new VectorStoreException(ErrorCode.VECTOR_BAD_RESPONSE,
                    "The vector search response is missing a score.");
        }
        if (Float.isNaN(score) || Float.isInfinite(score)) {
            throw new VectorStoreException(ErrorCode.VECTOR_BAD_RESPONSE,
                    "The vector search response returned a non-finite score.");
        }
        return score.doubleValue();
    }

    private static UUID parseFileId(Map<String, Object> entity) {
        Object raw = entity == null ? null : entity.get(MilvusVectorEntityMapper.FIELD_FILE_ID);
        if (!(raw instanceof String value)) {
            throw new VectorStoreException(ErrorCode.VECTOR_BAD_RESPONSE,
                    "The vector search response is missing the file id scalar.");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            throw new VectorStoreException(ErrorCode.VECTOR_BAD_RESPONSE,
                    "The vector search response returned an illegal file id.");
        }
    }
}
