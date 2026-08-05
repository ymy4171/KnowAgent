package com.knowagent.knowledge.vector;

import java.util.List;
import java.util.UUID;

public interface VectorStoreGateway {

    void upsert(List<VectorChunk> chunks);

    List<VectorHit> search(VectorQuery query);

    void deleteByFile(UUID tenantId, UUID knowledgeBaseId, UUID fileId);
}

