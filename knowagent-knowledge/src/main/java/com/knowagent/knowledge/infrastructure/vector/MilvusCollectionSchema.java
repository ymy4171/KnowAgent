package com.knowagent.knowledge.infrastructure.vector;

import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;

/**
 * Builds the {@link CreateCollectionReq} for the fixed collection contract
 * (ADR-0005): VARCHAR primary key {@code id} (autoID=false) holding PostgreSQL
 * chunk UUID strings, a FLOAT_VECTOR {@code embedding} field of the validated
 * dimension and the retrieval scalars. The chunk body stays in PostgreSQL.
 */
final class MilvusCollectionSchema {

    private MilvusCollectionSchema() {
    }

    static CreateCollectionReq build(MilvusVectorProperties properties) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .build();
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusVectorEntityMapper.FIELD_ID)
                .dataType(DataType.VarChar)
                .maxLength(MilvusVectorProperties.UUID_FIELD_MAX_LENGTH)
                .isPrimaryKey(true)
                .autoID(false)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusVectorEntityMapper.FIELD_TENANT_ID)
                .dataType(DataType.VarChar)
                .maxLength(MilvusVectorProperties.UUID_FIELD_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusVectorEntityMapper.FIELD_KNOWLEDGE_BASE_ID)
                .dataType(DataType.VarChar)
                .maxLength(MilvusVectorProperties.UUID_FIELD_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusVectorEntityMapper.FIELD_FILE_ID)
                .dataType(DataType.VarChar)
                .maxLength(MilvusVectorProperties.UUID_FIELD_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusVectorEntityMapper.FIELD_CHUNK_ID)
                .dataType(DataType.VarChar)
                .maxLength(MilvusVectorProperties.UUID_FIELD_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusVectorEntityMapper.FIELD_MODEL_SPEC)
                .dataType(DataType.VarChar)
                .maxLength(MilvusVectorProperties.MODEL_SPEC_FIELD_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusVectorStoreAdapter.VECTOR_FIELD)
                .dataType(DataType.FloatVector)
                .dimension(properties.dimension())
                .build());
        return CreateCollectionReq.builder()
                .collectionName(properties.collectionName())
                .collectionSchema(schema)
                .build();
    }
}
