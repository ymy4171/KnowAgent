package com.knowagent.knowledge.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.file.KnowledgeFilePage;
import com.knowagent.knowledge.file.KnowledgeFileStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for knowledge files. The tenant id is always supplied by the caller
 * (from the authenticated principal); it is never parsed from a request. All operations
 * are tenant-scoped; {@code findById} and {@code page} are soft-delete aware while the
 * idempotency lookup deliberately sees deleted rows too, because replay semantics must
 * decide on the whole upload history for a key, not just the live rows.
 */
public interface KnowledgeFileRepository {

    /** Inserts a new (already enqueued) knowledge file. */
    void save(KnowledgeFile file);

    /** Finds a live (non-deleted) file by tenant, knowledge base and id. */
    Optional<KnowledgeFile> findById(TenantId tenantId, UUID knowledgeBaseId, UUID id);

    /** Worker lookup when the trusted event only carries tenant + aggregate file id. */
    Optional<KnowledgeFile> findByTenantAndId(TenantId tenantId, UUID id);

    /**
     * Finds a live file while taking a row lock ({@code FOR UPDATE}) for the chunk
     * replacement transaction, serializing concurrent retries of the same file.
     */
    Optional<KnowledgeFile> findByIdForUpdate(TenantId tenantId, UUID knowledgeBaseId, UUID id);

    /** Worker row lock scoped by the required tenant_id + file_id pair. */
    Optional<KnowledgeFile> findByTenantAndIdForUpdate(TenantId tenantId, UUID id);

    /**
     * Applies one file-state transition guarded by tenant, current status and
     * version. Error text must already be stable and sanitized by the caller.
     */
    boolean transitionStatus(KnowledgeFile current, KnowledgeFile target);

    /**
     * Conditionally records {@code chunkCount}/{@code tokenCount}, bumping the file version;
     * returns {@code false} when the given version no longer matches (lost update).
     */
    boolean updateChunkStatistics(TenantId tenantId, UUID knowledgeBaseId, UUID id,
                                  int chunkCount, long tokenCount, long version);

    /**
     * Finds the most recent upload for {@code (tenant, knowledgeBase, key)}, deleted or
     * not, so the idempotency path can decide between replay and conflict on the whole
     * upload history.
     */
    Optional<KnowledgeFile> findByUploadIdempotencyKey(TenantId tenantId, UUID knowledgeBaseId, String key);

    /** Live files of a knowledge base, optionally filtered by status, newest first. */
    KnowledgeFilePage page(TenantId tenantId, UUID knowledgeBaseId,
                           KnowledgeFileStatus status, int page, int size);
}
