package com.knowagent.knowledge.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.knowledge.knowledgebase.KnowledgeBasePage;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for knowledge bases. The tenant id is always supplied by the caller
 * (from the authenticated principal); it is never parsed from a request. All
 * operations are tenant-scoped and soft-delete aware.
 */
public interface KnowledgeBaseRepository {

    void save(KnowledgeBase knowledgeBase);

    Optional<KnowledgeBase> findById(TenantId tenantId, UUID id);

    /** Locks the active knowledge-base row until the surrounding transaction completes. */
    Optional<KnowledgeBase> findByIdForUpdate(TenantId tenantId, UUID id);

    /**
     * Acquires a shared reference lock for file creation. It conflicts with the
     * deletion path's {@code FOR UPDATE} lock without serializing concurrent uploads.
     */
    Optional<KnowledgeBase> findByIdForKeyShare(TenantId tenantId, UUID id);

    Optional<KnowledgeBase> findActiveBySlug(TenantId tenantId, String slug);

    KnowledgeBasePage page(TenantId tenantId, String namePattern, String slugPattern,
                           KnowledgeBaseStatus status, int page, int size);

    /** Version-guarded update; returns the number of affected rows (0 = conflict). */
    int updateConfig(KnowledgeBase knowledgeBase);

    /** Version-guarded soft delete; returns the number of affected rows (0 = conflict). */
    int softDelete(TenantId tenantId, UUID id, long version);
}
