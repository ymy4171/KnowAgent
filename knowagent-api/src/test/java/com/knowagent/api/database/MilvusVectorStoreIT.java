package com.knowagent.api.database;

import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.infrastructure.vector.MilvusCollectionInitializer;
import com.knowagent.knowledge.infrastructure.vector.MilvusVectorProperties;
import com.knowagent.knowledge.infrastructure.vector.MilvusVectorStoreFactory;
import com.knowagent.knowledge.infrastructure.vector.MilvusVectorStoreFactory.MilvusVectorStoreComponents;
import com.knowagent.knowledge.infrastructure.vector.VectorStoreException;
import com.knowagent.knowledge.vector.VectorChunk;
import com.knowagent.knowledge.vector.VectorHit;
import com.knowagent.knowledge.vector.VectorQuery;
import com.knowagent.knowledge.vector.VectorStoreGateway;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.milvus.MilvusContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the Milvus vector-store adapter and collection initializer against a
 * real Milvus 2.5.6 server (Testcontainers, pinned to the same image as
 * docker-compose). It locks the collection contract (VARCHAR chunk-UUID primary
 * key, FLOAT_VECTOR dimension, tenant/kb/file/chunk/model-spec scalars), COSINE
 * search with tenant + knowledge-base + file filtering, entity-id equality with
 * PostgreSQL chunk UUIDs, idempotent upsert/delete and startup refusal on an
 * incompatible schema without dropping existing data.
 */
@Testcontainers
class MilvusVectorStoreIT {

    private static final String COLLECTION = "it_knowledge_chunks";
    private static final int DIMENSION = 4;
    private static final String MODEL_SPEC = "openai-compatible/text-embedding-it";

    @Container
    static final MilvusContainer MILVUS = new MilvusContainer("milvusdb/milvus:v2.5.6");

    private static MilvusClientV2 client;
    private static MilvusVectorStoreComponents components;
    private static MilvusVectorProperties properties;

    @BeforeAll
    static void provision() {
        properties = properties(COLLECTION, DIMENSION);
        client = MilvusVectorStoreFactory.connect(ConnectConfig.builder()
                .uri(MILVUS.getEndpoint())
                .connectTimeoutMs(10_000)
                .rpcDeadlineMs(60_000)
                .build(), Duration.ofMinutes(3));
        components = MilvusVectorStoreFactory.create(client, properties, null);
        components.initializer().start();
    }

    @AfterAll
    static void closeClient() throws Exception {
        if (components != null) {
            components.close();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    void initializesIdempotentlyAndKeepsServingData() {
        TenantId tenant = TenantId.of(UUID.randomUUID());
        UUID kb = UUID.randomUUID();
        UUID file = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        components.gateway().upsert(List.of(chunk(tenant, kb, file, chunkId, vector(1f, 0f, 0f, 0f))));

        // Re-running the initializer only validates the existing collection.
        components.initializer().start();

        List<VectorHit> hits = components.gateway().search(query(tenant, kb, vector(1f, 0f, 0f, 0f)));
        assertThat(hits).extracting(VectorHit::chunkId).containsExactly(chunkId);
    }

    @Test
    void searchFiltersByTenantKnowledgeBaseAndFile() {
        TenantId tenantA = TenantId.of(UUID.randomUUID());
        TenantId tenantB = TenantId.of(UUID.randomUUID());
        UUID kbA = UUID.randomUUID();
        UUID fileA1 = UUID.randomUUID();
        UUID fileA2 = UUID.randomUUID();
        UUID chunkA1 = UUID.randomUUID();
        UUID chunkA2 = UUID.randomUUID();
        UUID chunkA3 = UUID.randomUUID();
        UUID chunkA4 = UUID.randomUUID();
        // tenant B stores the exact same vector as chunk A1: isolation must hold.
        UUID chunkB1 = UUID.randomUUID();

        components.gateway().upsert(List.of(
                chunk(tenantA, kbA, fileA1, chunkA1, vector(1f, 0f, 0f, 0f)),
                chunk(tenantA, kbA, fileA1, chunkA2, vector(0f, 1f, 0f, 0f)),
                chunk(tenantA, kbA, fileA1, chunkA3, vector(0f, 0f, 1f, 0f))));
        components.gateway().upsert(List.of(
                chunk(tenantA, kbA, fileA2, chunkA4, vector(0f, 0f, 0f, 1f))));
        components.gateway().upsert(List.of(
                chunk(tenantB, UUID.randomUUID(), UUID.randomUUID(), chunkB1, vector(1f, 0f, 0f, 0f))));

        List<VectorHit> all = components.gateway().search(query(tenantA, kbA, vector(1f, 0f, 0f, 0f)));
        assertThat(all).extracting(VectorHit::chunkId)
                .containsExactlyInAnyOrder(chunkA1, chunkA2, chunkA3, chunkA4)
                .doesNotContain(chunkB1);
        assertThat(all).allSatisfy(hit -> assertThat(hit.fileId()).isIn(fileA1, fileA2));

        List<VectorHit> onlyFile = components.gateway().search(
                query(tenantA, kbA, vector(1f, 0f, 0f, 0f), List.of(fileA1)));
        assertThat(onlyFile).extracting(VectorHit::chunkId).containsExactlyInAnyOrder(chunkA1, chunkA2, chunkA3);
    }

    @Test
    void tenantAQueryWithTenantBChunkOrFileIdsGetsNoResults() {
        TenantId tenantA = TenantId.of(UUID.randomUUID());
        TenantId tenantB = TenantId.of(UUID.randomUUID());
        UUID kbA = UUID.randomUUID();
        UUID fileA = UUID.randomUUID();
        UUID fileB = UUID.randomUUID();
        UUID chunkA = UUID.randomUUID();
        UUID chunkB = UUID.randomUUID();

        components.gateway().upsert(List.of(
                chunk(tenantA, kbA, fileA, chunkA, vector(1f, 0f, 0f, 0f))));
        components.gateway().upsert(List.of(
                chunk(tenantB, UUID.randomUUID(), fileB, chunkB, vector(1f, 0f, 0f, 0f))));

        // tenant-A querying with tenant-B's file id: the file clause is tenant-scoped,
        // so nothing matches even though the vector is identical.
        List<VectorHit> crossTenantFile = components.gateway().search(
                query(tenantA, kbA, vector(1f, 0f, 0f, 0f), List.of(fileB)));
        assertThat(crossTenantFile).isEmpty();

        // tenant-A querying tenant-B's knowledge base: no rows match the kb clause.
        UUID kbB = UUID.randomUUID();
        components.gateway().upsert(List.of(chunk(tenantB, kbB, fileB, UUID.randomUUID(), vector(1f, 0f, 0f, 0f))));
        List<VectorHit> crossTenantKb = components.gateway().search(query(tenantA, kbB, vector(1f, 0f, 0f, 0f)));
        assertThat(crossTenantKb).isEmpty();

        // tenant-A deleting tenant-B's file must not remove tenant-A rows.
        components.gateway().deleteByFile(tenantA.value(), kbA, fileB);
        assertThat(countEntities(tenantA, kbA, fileA)).isEqualTo(1);
        assertThat(countEntities(tenantB, kbB, fileB)).isEqualTo(1);
    }

    @Test
    void repeatedUpsertDoesNotDuplicateEntitiesAndRepeatedDeleteSucceeds() {
        TenantId tenant = TenantId.of(UUID.randomUUID());
        UUID kb = UUID.randomUUID();
        UUID file = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();

        components.gateway().upsert(List.of(chunk(tenant, kb, file, chunkId, vector(1f, 0f, 0f, 0f))));
        // Re-upserting the same chunk ids replaces, never duplicates (upsert semantics).
        components.gateway().upsert(List.of(chunk(tenant, kb, file, chunkId, vector(0.9f, 0.1f, 0f, 0f))));

        awaitUntil(() -> countEntities(tenant, kb, file) == 1);

        components.gateway().deleteByFile(tenant.value(), kb, file);
        awaitUntil(() -> countEntities(tenant, kb, file) == 0);
        // Deleting again (and deleting an absent file) is an idempotent success.
        components.gateway().deleteByFile(tenant.value(), kb, file);
        components.gateway().deleteByFile(tenant.value(), kb, UUID.randomUUID());
    }

    @Test
    void postgresChunkUuidEqualsMilvusPrimaryKey() {
        TenantId tenant = TenantId.of(UUID.randomUUID());
        UUID kb = UUID.randomUUID();
        UUID file = UUID.randomUUID();
        UUID chunkId1 = UUID.randomUUID();
        UUID chunkId2 = UUID.randomUUID();

        components.gateway().upsert(List.of(
                chunk(tenant, kb, file, chunkId1, vector(1f, 0f, 0f, 0f)),
                chunk(tenant, kb, file, chunkId2, vector(0f, 1f, 0f, 0f))));

        QueryResp query = client.query(QueryReq.builder()
                .collectionName(COLLECTION)
                .filter("id in ['" + chunkId1 + "','" + chunkId2 + "']")
                .outputFields(List.of("id", "chunk_id", "tenant_id", "knowledge_base_id", "file_id"))
                .consistencyLevel(io.milvus.v2.common.ConsistencyLevel.STRONG)
                .limit(100)
                .build());
        assertThat(query.getQueryResults()).hasSize(2);
        query.getQueryResults().forEach(result -> {
            String id = (String) result.getEntity().get("id");
            String chunkId = (String) result.getEntity().get("chunk_id");
            assertThat(chunkId).isEqualTo(id); // entity id == PostgreSQL chunk UUID
            assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            assertThat(result.getEntity().get("tenant_id")).isEqualTo(tenant.value().toString());
            assertThat(result.getEntity().get("knowledge_base_id")).isEqualTo(kb.toString());
            assertThat(result.getEntity().get("file_id")).isEqualTo(file.toString());
        });
    }

    @Test
    void anIncompatibleDimensionRefusesStartupWithoutDroppingExistingData() {
        String collection = "it_mismatch_collection";
        MilvusVectorProperties rightDimension = properties(collection, DIMENSION);
        try (MilvusVectorStoreComponents right = MilvusVectorStoreFactory.create(client, rightDimension, null)) {
            right.initializer().start();
            TenantId tenant = TenantId.of(UUID.randomUUID());
            UUID kb = UUID.randomUUID();
            UUID file = UUID.randomUUID();
            UUID chunkId = UUID.randomUUID();
            right.gateway().upsert(List.of(chunk(tenant, kb, file, chunkId, vector(1f, 0f, 0f, 0f))));

            // A second deployment configured with a different dimension must refuse
            // startup and must not drop or alter the existing collection.
            MilvusVectorProperties wrongDimension = properties(collection, DIMENSION + 4);
            try (MilvusVectorStoreComponents wrong = MilvusVectorStoreFactory.create(client, wrongDimension, null)) {
                assertThatThrownBy(wrong.initializer()::start)
                        .isInstanceOf(VectorStoreException.class)
                        .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                                .isEqualTo(ErrorCode.VECTOR_SCHEMA_MISMATCH));
            }

            // The data is untouched and still searchable through the right dimension.
            List<VectorHit> hits = right.gateway().search(query(tenant, kb, vector(1f, 0f, 0f, 0f)));
            assertThat(hits).extracting(VectorHit::chunkId).containsExactly(chunkId);
        }
    }

    @Test
    void hnswIndexTypeAndBuildParametersAreValidatedOnRestart() {
        String collection = "it_hnsw_contract";
        MilvusVectorProperties expected = properties(collection, DIMENSION, "HNSW", 16, 64);
        try (MilvusVectorStoreComponents right = MilvusVectorStoreFactory.create(client, expected, null)) {
            right.initializer().start();
            // The existing-collection path consumes the real Milvus description,
            // including index_type and the server-normalized params map.
            right.initializer().start();

            MilvusVectorProperties wrongM = properties(collection, DIMENSION, "HNSW", 32, 64);
            try (MilvusVectorStoreComponents wrong = MilvusVectorStoreFactory.create(client, wrongM, null)) {
                assertThatThrownBy(wrong.initializer()::start)
                        .isInstanceOf(VectorStoreException.class)
                        .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                                .isEqualTo(ErrorCode.VECTOR_SCHEMA_MISMATCH));
            }
        }
    }

    private static MilvusVectorProperties properties(String collection, int dimension) {
        return properties(collection, dimension, "FLAT", 16, 64);
    }

    private static MilvusVectorProperties properties(String collection, int dimension, String indexType,
                                                      int m, int efConstruction) {
        return new MilvusVectorProperties(
                MILVUS.getEndpoint(), null, null, null, null, collection, dimension, indexType,
                m, efConstruction, 64, Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(30),
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(300));
    }

    private static VectorChunk chunk(TenantId tenant, UUID kb, UUID file, UUID chunkId, float[] vector) {
        return new VectorChunk(tenant, kb, file, chunkId, "chunk body text", vector, MODEL_SPEC);
    }

    private static VectorQuery query(TenantId tenant, UUID kb, float[] vector) {
        return query(tenant, kb, vector, null);
    }

    private static VectorQuery query(TenantId tenant, UUID kb, float[] vector, List<UUID> fileIds) {
        return new VectorQuery(tenant, kb, vector, 16, -1.0, fileIds);
    }

    private static float[] vector(float x, float y, float z, float w) {
        return new float[]{x, y, z, w};
    }

    private long countEntities(TenantId tenant, UUID kb, UUID file) {
        QueryResp query = client.query(QueryReq.builder()
                .collectionName(COLLECTION)
                .filter("tenant_id == '" + tenant.value() + "' && knowledge_base_id == '" + kb
                        + "' && file_id == '" + file + "'")
                .outputFields(List.of("id"))
                .consistencyLevel(io.milvus.v2.common.ConsistencyLevel.STRONG)
                .limit(10_000)
                .build());
        return query.getQueryResults().size();
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting Milvus state", interrupted);
            }
        }
        throw new AssertionError("Milvus state did not converge within 30s");
    }
}
