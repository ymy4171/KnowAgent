package com.knowagent.knowledge.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.chunk.KnowledgeChunk;
import com.knowagent.knowledge.chunk.ChunkIndexStatus;

import java.util.List;
import java.util.UUID;

/**
 * Persistence port for {@code knowledge_chunks}. All operations are tenant-scoped and keyed
 * by the explicit {@code (tenant, knowledge base, file)} triple - a bare file UUID is never
 * enough to read, replace or delete another tenant's chunks.
 *
 * <p>{@link #replaceAll} implements the idempotent retry contract: it deletes the file's
 * current chunk set and inserts the new one inside the surrounding transaction. Because the
 * position uniqueness is {@code (tenant_id, file_id, chunk_index)}, a retry can never
 * produce duplicate indices.
 */
public interface KnowledgeChunkRepository {

    /** Replaces the file's whole chunk set; an empty list deletes the previous set. */
    void replaceAll(TenantId tenantId, UUID knowledgeBaseId, UUID fileId, List<KnowledgeChunk> chunks);

    /** Reads a file's chunks ordered by chunk index. */
    List<KnowledgeChunk> findByFile(TenantId tenantId, UUID knowledgeBaseId, UUID fileId);

    /** Bulk guarded status transition for every current chunk of one file. */
    int transitionIndexStatus(TenantId tenantId, UUID knowledgeBaseId, UUID fileId,
                              ChunkIndexStatus expected, ChunkIndexStatus target,
                              String embeddingModelSpec, String errorCode, String errorMessage);
}
