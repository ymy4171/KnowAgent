package com.knowagent.knowledge.vector;

import java.util.List;
import java.util.UUID;

/**
 * Vector-store port implemented by the Milvus adapter. The application layer only
 * sees these domain types - Milvus SDK types never cross this boundary.
 *
 * <p>Contract (fixed by ADR-0005):
 * <ul>
 *   <li>every write/search/delete is scoped by {@code tenant_id} and
 *       {@code knowledge_base_id} (plus {@code file_id} when present);</li>
 *   <li>the Milvus entity id equals the PostgreSQL chunk UUID;</li>
 *   <li>search returns only id, score and the minimal scalars needed to re-hydrate
 *       content from PostgreSQL;</li>
 *   <li>deleting an absent file is an idempotent success (compensation semantics).</li>
 * </ul>
 */
public interface VectorStoreGateway {

    void upsert(List<VectorChunk> chunks);

    List<VectorHit> search(VectorQuery query);

    void deleteByFile(UUID tenantId, UUID knowledgeBaseId, UUID fileId);
}
