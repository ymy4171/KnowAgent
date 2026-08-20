package com.knowagent.knowledge.application.port.out;

import com.knowagent.common.tenant.TenantId;

import java.util.List;
import java.util.UUID;

/** PostgreSQL bulk hydration port for vector candidate chunk ids. */
public interface KnowledgeRetrievalRepository {

    /**
     * Loads candidates only within the explicit tenant and knowledge-base scope.
     * Callers must not assume the returned order matches {@code chunkIds}; vector
     * ranking is restored in the application layer.
     */
    List<RetrievalChunkRecord> findByChunkIds(TenantId tenantId, UUID knowledgeBaseId,
                                              List<UUID> chunkIds);
}
