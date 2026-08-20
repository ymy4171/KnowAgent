package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.ErrorCode;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the startup provisioning contract: an absent collection is created with the
 * fixed schema/index and loaded; an existing compatible collection is validated
 * and loaded (never dropped); an incompatible one refuses startup.
 */
class MilvusCollectionInitializerTest {

    private static final int DIMENSION = 4;

    @Test
    void createsIndexesAndLoadsAnAbsentCollection() {
        FakeClient client = new FakeClient(false);
        MilvusCollectionInitializer initializer = new MilvusCollectionInitializer(
                client, properties(), new MilvusCallExecutor(new VectorMetrics(null)));

        initializer.start();

        assertThat(client.created).hasSize(1);
        assertThat(client.indexes).hasSize(1);
        assertThat(client.loaded).isTrue();
        assertThat(initializer.isRunning()).isTrue();

        CreateCollectionReq.FieldSchema idField = client.created.get(0).getCollectionSchema().getField("id");
        assertThat(idField.getDataType()).isEqualTo(DataType.VarChar);
        assertThat(idField.getIsPrimaryKey()).isTrue();
        assertThat(idField.getAutoID()).isFalse();

        CreateCollectionReq.FieldSchema embedding = client.created.get(0).getCollectionSchema().getField("embedding");
        assertThat(embedding.getDataType()).isEqualTo(DataType.FloatVector);
        assertThat(embedding.getDimension()).isEqualTo(DIMENSION);

        IndexParam index = client.indexes.get(0);
        assertThat(index.getFieldName()).isEqualTo("embedding");
        assertThat(index.getMetricType()).isEqualTo(IndexParam.MetricType.COSINE);
    }

    @Test
    void anExistingCompatibleCollectionIsValidatedAndLoaded() {
        FakeClient client = new FakeClient(true);
        MilvusCollectionInitializer initializer = new MilvusCollectionInitializer(
                client, properties(), new MilvusCallExecutor(new VectorMetrics(null)));

        initializer.start();

        assertThat(client.created).isEmpty();
        assertThat(client.indexes).isEmpty();
        assertThat(client.loaded).isTrue();
        assertThat(client.described).isTrue();
    }

    @Test
    void anIncompatibleExistingCollectionRefusesStartup() {
        FakeClient client = new FakeClient(true);
        client.existingDimension = 8; // mismatch with the configured dimension 4
        MilvusCollectionInitializer initializer = new MilvusCollectionInitializer(
                client, properties(), new MilvusCallExecutor(new VectorMetrics(null)));

        assertThatThrownBy(initializer::start)
                .isInstanceOf(VectorStoreException.class)
                .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                        .isEqualTo(ErrorCode.VECTOR_SCHEMA_MISMATCH));
        // The existing collection is never dropped or altered.
        assertThat(client.created).isEmpty();
        assertThat(client.indexes).isEmpty();
    }

    private static MilvusVectorProperties properties() {
        return new MilvusVectorProperties(
                "http://localhost:19530", null, null, null, null, "test_collection", DIMENSION, "HNSW",
                16, 64, 64, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(120));
    }

    private static final class FakeClient implements MilvusClientAccess {
        private final boolean existing;
        private int existingDimension = DIMENSION;
        private final List<CreateCollectionReq> created = new ArrayList<>();
        private final List<IndexParam> indexes = new ArrayList<>();
        private boolean loaded;
        private boolean described;

        FakeClient(boolean existing) {
            this.existing = existing;
        }

        @Override
        public boolean hasCollection(String collectionName) {
            return existing;
        }

        @Override
        public void createCollection(CreateCollectionReq request) {
            created.add(request);
        }

        @Override
        public void createCollectionIndex(String collectionName, IndexParam index, long timeoutMillis) {
            indexes.add(index);
        }

        @Override
        public DescribeCollectionResp describeCollection(String collectionName) {
            described = true;
            CreateCollectionReq.FieldSchema id = CreateCollectionReq.FieldSchema.builder()
                    .name("id").dataType(DataType.VarChar).maxLength(64).isPrimaryKey(true).autoID(false).build();
            CreateCollectionReq.FieldSchema tenant = CreateCollectionReq.FieldSchema.builder()
                    .name("tenant_id").dataType(DataType.VarChar).maxLength(64).build();
            CreateCollectionReq.FieldSchema kb = CreateCollectionReq.FieldSchema.builder()
                    .name("knowledge_base_id").dataType(DataType.VarChar).maxLength(64).build();
            CreateCollectionReq.FieldSchema file = CreateCollectionReq.FieldSchema.builder()
                    .name("file_id").dataType(DataType.VarChar).maxLength(64).build();
            CreateCollectionReq.FieldSchema chunk = CreateCollectionReq.FieldSchema.builder()
                    .name("chunk_id").dataType(DataType.VarChar).maxLength(64).build();
            CreateCollectionReq.FieldSchema spec = CreateCollectionReq.FieldSchema.builder()
                    .name("embedding_model_spec").dataType(DataType.VarChar).maxLength(512).build();
            CreateCollectionReq.FieldSchema embedding = CreateCollectionReq.FieldSchema.builder()
                    .name("embedding").dataType(DataType.FloatVector).dimension(existingDimension).build();
            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                    .fieldSchemaList(List.of(id, tenant, kb, file, chunk, spec, embedding))
                    .build();
            return DescribeCollectionResp.builder()
                    .collectionName(collectionName)
                    .primaryFieldName("id")
                    .collectionSchema(schema)
                    .build();
        }

        @Override
        public DescribeIndexResp describeIndex(String collectionName) {
            DescribeIndexResp.IndexDesc indexDesc = DescribeIndexResp.IndexDesc.builder()
                    .fieldName("embedding")
                    .indexName("embedding_index")
                    .indexType(IndexParam.IndexType.HNSW)
                    .metricType(IndexParam.MetricType.COSINE)
                    .extraParams(java.util.Map.of("M", "16", "efConstruction", "64"))
                    .build();
            return DescribeIndexResp.builder().indexDescriptions(List.of(indexDesc)).build();
        }

        @Override
        public void loadCollection(String collectionName, long timeoutMillis) {
            loaded = true;
        }

        @Override
        public io.milvus.v2.service.vector.response.UpsertResp upsert(String collectionName,
                                                                      List<com.google.gson.JsonObject> rows) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.milvus.v2.service.vector.response.SearchResp search(
                io.milvus.v2.service.vector.request.SearchReq request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.milvus.v2.service.vector.response.DeleteResp delete(String collectionName, String filter) {
            throw new UnsupportedOperationException();
        }
    }
}
