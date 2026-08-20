package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.ErrorCode;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.response.DescribeIndexResp;

import java.util.Map;
import java.util.Objects;

/**
 * Validates an existing Milvus collection against the fixed collection contract
 * (ADR-0005): VARCHAR primary key {@code id} holding PostgreSQL chunk UUIDs with
 * autoID=false, a FLOAT_VECTOR {@code embedding} field with the configured
 * dimension, the tenant/knowledge-base/file/chunk/model-spec scalar fields and a
 * COSINE index on the vector field. A mismatch refuses startup with
 * VECTOR_SCHEMA_MISMATCH - an existing collection is never dropped or altered.
 */
final class MilvusSchemaValidator {

    private MilvusSchemaValidator() {
    }

    static void validate(DescribeCollectionResp collection, DescribeIndexResp index,
                         MilvusVectorProperties properties) {
        Objects.requireNonNull(collection, "collection must not be null");
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        int expectedDimension = properties.dimension();

        CreateCollectionReq.FieldSchema idField = collection.getCollectionSchema().getField(
                MilvusVectorEntityMapper.FIELD_ID);
        if (idField == null || idField.getDataType() != DataType.VarChar || !Boolean.TRUE.equals(idField.getIsPrimaryKey())) {
            throw mismatch("The vector store collection primary key must be a VARCHAR field named '"
                    + MilvusVectorEntityMapper.FIELD_ID + "'.");
        }
        if (Boolean.TRUE.equals(idField.getAutoID())) {
            throw mismatch("The vector store collection must not auto-generate entity ids.");
        }
        if (!MilvusVectorEntityMapper.FIELD_ID.equals(collection.getPrimaryFieldName())) {
            throw mismatch("The vector store collection primary field must be '"
                    + MilvusVectorEntityMapper.FIELD_ID + "'.");
        }

        CreateCollectionReq.FieldSchema embedding = collection.getCollectionSchema().getField(
                MilvusVectorStoreAdapter.VECTOR_FIELD);
        if (embedding == null || embedding.getDataType() != DataType.FloatVector) {
            throw mismatch("The vector store collection is missing the FLOAT_VECTOR '"
                    + MilvusVectorStoreAdapter.VECTOR_FIELD + "' field.");
        }
        Integer actualDimension = embedding.getDimension();
        if (actualDimension == null || actualDimension != expectedDimension) {
            throw mismatch("The vector store collection dimension " + actualDimension
                    + " does not match the configured dimension " + expectedDimension + ".");
        }

        for (String field : new String[]{MilvusVectorEntityMapper.FIELD_TENANT_ID,
                MilvusVectorEntityMapper.FIELD_KNOWLEDGE_BASE_ID,
                MilvusVectorEntityMapper.FIELD_FILE_ID,
                MilvusVectorEntityMapper.FIELD_CHUNK_ID,
                MilvusVectorEntityMapper.FIELD_MODEL_SPEC}) {
            CreateCollectionReq.FieldSchema scalar = collection.getCollectionSchema().getField(field);
            if (scalar == null || scalar.getDataType() != DataType.VarChar) {
                throw mismatch("The vector store collection is missing the VARCHAR scalar field '" + field + "'.");
            }
        }

        DescribeIndexResp.IndexDesc vectorIndex = index.getIndexDescriptions().stream()
                .filter(description -> MilvusVectorStoreAdapter.VECTOR_FIELD.equals(description.getFieldName()))
                .findFirst()
                .orElse(null);
        if (vectorIndex == null || vectorIndex.getMetricType() != IndexParam.MetricType.COSINE) {
            throw mismatch("The vector store collection must have a COSINE index on the '"
                    + MilvusVectorStoreAdapter.VECTOR_FIELD + "' field.");
        }
        IndexParam.IndexType expectedIndexType = IndexParam.IndexType.valueOf(properties.indexType());
        if (vectorIndex.getIndexType() != expectedIndexType) {
            throw mismatch("The vector store index type does not match the configured index type.");
        }
        if (expectedIndexType == IndexParam.IndexType.HNSW) {
            validateHnswParams(vectorIndex.getExtraParams(), properties);
        }
    }

    private static void validateHnswParams(Map<String, String> actual,
                                           MilvusVectorProperties properties) {
        if (actual == null
                || !Integer.toString(properties.m()).equals(actual.get("M"))
                || !Integer.toString(properties.efConstruction()).equals(actual.get("efConstruction"))) {
            throw mismatch("The vector store HNSW index parameters do not match the configured parameters.");
        }
    }

    private static VectorStoreException mismatch(String message) {
        return new VectorStoreException(ErrorCode.VECTOR_SCHEMA_MISMATCH, message);
    }
}
