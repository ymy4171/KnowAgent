package com.knowagent.knowledge.infrastructure.vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.vector.VectorChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the upsert input contract: the batch must be non-empty, ids must form a
 * consistent tenant/knowledge-base/file relation, vectors must match the configured
 * dimension and be finite, and the Milvus entity id must equal the chunk UUID.
 */
class MilvusVectorEntityMapperTest {

    private static final int DIMENSION = 4;
    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final UUID KB = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();
    private static final String MODEL_SPEC = "openai-compatible/text-embedding-test";

    @Test
    void mapsAValidChunkToAnUpsertRowWithChunkUuidAsEntityId() {
        UUID chunkId = UUID.randomUUID();
        List<JsonObject> rows = MilvusVectorEntityMapper.toRows(
                List.of(chunk(chunkId, new float[]{0.1f, 0.2f, 0.3f, 0.4f})), DIMENSION);

        assertThat(rows).hasSize(1);
        JsonObject row = rows.get(0);
        assertThat(row.get(MilvusVectorEntityMapper.FIELD_ID).getAsString()).isEqualTo(chunkId.toString());
        assertThat(row.get(MilvusVectorEntityMapper.FIELD_TENANT_ID).getAsString()).isEqualTo(TENANT.value().toString());
        assertThat(row.get(MilvusVectorEntityMapper.FIELD_KNOWLEDGE_BASE_ID).getAsString()).isEqualTo(KB.toString());
        assertThat(row.get(MilvusVectorEntityMapper.FIELD_FILE_ID).getAsString()).isEqualTo(FILE.toString());
        assertThat(row.get(MilvusVectorEntityMapper.FIELD_CHUNK_ID).getAsString()).isEqualTo(chunkId.toString());
        assertThat(row.get(MilvusVectorEntityMapper.FIELD_MODEL_SPEC).getAsString()).isEqualTo(MODEL_SPEC);

        JsonArray embedding = row.getAsJsonArray(MilvusVectorEntityMapper.FIELD_EMBEDDING);
        assertThat(embedding).hasSize(DIMENSION);
        assertThat(embedding.get(0).getAsFloat()).isEqualTo(0.1f);
        assertThat(embedding.get(3).getAsFloat()).isEqualTo(0.4f);
    }

    @Test
    void anEmptyBatchIsRejected() {
        assertThatThrownBy(() -> MilvusVectorEntityMapper.toRows(List.of(), DIMENSION))
                .isInstanceOf(VectorStoreException.class)
                .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> MilvusVectorEntityMapper.toRows(null, DIMENSION))
                .isInstanceOf(VectorStoreException.class);
    }

    @Test
    void aWrongDimensionVectorIsRejectedBeforeAnySdkCall() {
        assertThatThrownBy(() -> MilvusVectorEntityMapper.toRows(
                List.of(chunk(UUID.randomUUID(), new float[]{0.1f, 0.2f})), DIMENSION))
                .isInstanceOf(VectorStoreException.class)
                .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("dimension");
    }

    @Test
    void nonFiniteVectorValuesAreRejected() {
        assertThatThrownBy(() -> MilvusVectorEntityMapper.toRows(
                List.of(chunk(UUID.randomUUID(), new float[]{0.1f, Float.NaN, 0.3f, 0.4f})), DIMENSION))
                .isInstanceOf(VectorStoreException.class)
                .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> MilvusVectorEntityMapper.toRows(
                List.of(chunk(UUID.randomUUID(), new float[]{0.1f, Float.POSITIVE_INFINITY, 0.3f, 0.4f})), DIMENSION))
                .isInstanceOf(VectorStoreException.class);
    }

    @Test
    void duplicateChunkIdsInOneBatchAreRejected() {
        UUID chunkId = UUID.randomUUID();
        assertThatThrownBy(() -> MilvusVectorEntityMapper.toRows(
                List.of(chunk(chunkId, new float[]{0.1f, 0.2f, 0.3f, 0.4f}),
                        chunk(chunkId, new float[]{0.5f, 0.6f, 0.7f, 0.8f})), DIMENSION))
                .isInstanceOf(VectorStoreException.class)
                .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("more than once");
    }

    @Test
    void mixedTenantKnowledgeBaseOrFileBatchesAreRejected() {
        VectorChunk baseline = chunk(UUID.randomUUID(), new float[]{0.1f, 0.2f, 0.3f, 0.4f});
        assertRelationMismatch(baseline, chunk(TenantId.of(UUID.randomUUID()), KB, FILE));
        assertRelationMismatch(baseline, chunk(TENANT, UUID.randomUUID(), FILE));
        assertRelationMismatch(baseline, chunk(TENANT, KB, UUID.randomUUID()));
    }

    private static void assertRelationMismatch(VectorChunk baseline, VectorChunk different) {
        assertThatThrownBy(() -> MilvusVectorEntityMapper.toRows(List.of(baseline, different), DIMENSION))
                .isInstanceOf(VectorStoreException.class)
                .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("one tenant, knowledge base and file");
    }

    private static VectorChunk chunk(TenantId tenant, UUID kb, UUID file) {
        return new VectorChunk(tenant, kb, file, UUID.randomUUID(), "body",
                new float[]{0.1f, 0.2f, 0.3f, 0.4f}, MODEL_SPEC);
    }

    private static VectorChunk chunk(UUID chunkId, float[] embedding) {
        return new VectorChunk(TENANT, KB, FILE, chunkId, "body", embedding, MODEL_SPEC);
    }
}
