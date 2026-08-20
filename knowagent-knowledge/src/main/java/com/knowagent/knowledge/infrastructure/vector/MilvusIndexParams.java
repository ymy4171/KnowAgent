package com.knowagent.knowledge.infrastructure.vector;

import io.milvus.v2.common.IndexParam;

import java.util.Map;

/**
 * Builds the Milvus index and search parameter maps from the validated
 * configuration. The metric is always COSINE (fixed by the collection contract);
 * the index type and its parameters are fixed by configuration (ADR-0005).
 * HNSW uses {@code M}/{@code efConstruction} for the index and {@code ef} for
 * search; FLAT and AUTOINDEX take no parameters.
 */
final class MilvusIndexParams {

    private MilvusIndexParams() {
    }

    static IndexParam buildIndex(MilvusVectorProperties properties) {
        return IndexParam.builder()
                .fieldName(MilvusVectorStoreAdapter.VECTOR_FIELD)
                .indexType(IndexParam.IndexType.valueOf(properties.indexType()))
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(indexExtraParams(properties))
                .build();
    }

    static Map<String, Object> searchParams(MilvusVectorProperties properties) {
        if (!MilvusVectorProperties.INDEX_HNSW.equals(properties.indexType())) {
            return Map.of();
        }
        return Map.of("ef", properties.searchEf());
    }

    private static Map<String, Object> indexExtraParams(MilvusVectorProperties properties) {
        if (!MilvusVectorProperties.INDEX_HNSW.equals(properties.indexType())) {
            return Map.of();
        }
        return Map.of("M", properties.m(), "efConstruction", properties.efConstruction());
    }
}
