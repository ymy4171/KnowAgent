package com.knowagent.knowledge.infrastructure.vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.vector.VectorChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Maps {@link VectorChunk} domain objects to Milvus upsert rows (Gson
 * {@link JsonObject}) and validates the batch before any SDK call.
 *
 * <p>Milvus only stores what retrieval needs: the entity id (equal to the
 * PostgreSQL chunk UUID, VARCHAR primary key with autoID=false), the tenant /
 * knowledge-base / file / chunk scalars, the embedding-model spec and the
 * embedding vector. The chunk body stays in PostgreSQL.
 */
final class MilvusVectorEntityMapper {

    static final String FIELD_ID = "id";
    static final String FIELD_TENANT_ID = "tenant_id";
    static final String FIELD_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    static final String FIELD_FILE_ID = "file_id";
    static final String FIELD_CHUNK_ID = "chunk_id";
    static final String FIELD_MODEL_SPEC = "embedding_model_spec";
    static final String FIELD_EMBEDDING = "embedding";

    private MilvusVectorEntityMapper() {
    }

    /**
     * @param expectedDimension the configured collection dimension; every vector must
     *                          have exactly this length
     */
    static List<JsonObject> toRows(List<VectorChunk> chunks, int expectedDimension) {
        if (chunks == null || chunks.isEmpty()) {
            throw new VectorStoreException(ErrorCode.VALIDATION_ERROR,
                    "The vector upsert batch must not be empty.");
        }
        VectorChunk first = chunks.get(0);
        if (first == null) {
            throw new VectorStoreException(ErrorCode.VALIDATION_ERROR,
                    "The vector upsert batch must not contain null chunks.");
        }
        requireId(first.tenantId().value(), "tenantId");
        requireId(first.knowledgeBaseId(), "knowledgeBaseId");
        requireId(first.fileId(), "fileId");

        Set<String> seenIds = new HashSet<>();
        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (VectorChunk chunk : chunks) {
            if (chunk == null) {
                throw new VectorStoreException(ErrorCode.VALIDATION_ERROR,
                        "The vector upsert batch must not contain null chunks.");
            }
            if (!first.tenantId().equals(chunk.tenantId())
                    || !first.knowledgeBaseId().equals(chunk.knowledgeBaseId())
                    || !first.fileId().equals(chunk.fileId())) {
                throw new VectorStoreException(ErrorCode.VALIDATION_ERROR,
                        "Every vector upsert batch must belong to one tenant, knowledge base and file.");
            }
            rows.add(toRow(chunk, expectedDimension, seenIds));
        }
        return List.copyOf(rows);
    }

    private static JsonObject toRow(VectorChunk chunk, int expectedDimension, Set<String> seenIds) {
        requireId(chunk.tenantId().value(), "tenantId");
        requireId(chunk.knowledgeBaseId(), "knowledgeBaseId");
        requireId(chunk.fileId(), "fileId");
        requireId(chunk.chunkId(), "chunkId");

        float[] embedding = chunk.embedding();
        if (embedding.length != expectedDimension) {
            throw new VectorStoreException(ErrorCode.VALIDATION_ERROR,
                    "Vector dimension " + embedding.length + " does not match the configured collection dimension "
                            + expectedDimension + ".");
        }
        for (float value : embedding) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new VectorStoreException(ErrorCode.VALIDATION_ERROR,
                        "Vector values must all be finite.");
            }
        }

        String entityId = chunk.chunkId().toString();
        if (!seenIds.add(entityId)) {
            throw new VectorStoreException(ErrorCode.VALIDATION_ERROR,
                    "The upsert batch contains the chunk id " + entityId + " more than once.");
        }

        JsonArray vector = new JsonArray(embedding.length);
        for (float value : embedding) {
            vector.add(value);
        }

        JsonObject row = new JsonObject();
        // Milvus entity id MUST equal the PostgreSQL chunk UUID (one-to-one mapping).
        row.addProperty(FIELD_ID, entityId);
        row.addProperty(FIELD_TENANT_ID, chunk.tenantId().value().toString());
        row.addProperty(FIELD_KNOWLEDGE_BASE_ID, chunk.knowledgeBaseId().toString());
        row.addProperty(FIELD_FILE_ID, chunk.fileId().toString());
        row.addProperty(FIELD_CHUNK_ID, entityId);
        row.addProperty(FIELD_MODEL_SPEC, chunk.embeddingModelSpec());
        row.add(FIELD_EMBEDDING, vector);
        return row;
    }

    private static void requireId(UUID value, String field) {
        if (value == null) {
            throw new VectorStoreException(ErrorCode.VALIDATION_ERROR,
                    "The vector chunk " + field + " must not be null.");
        }
    }
}
