package com.knowagent.knowledge.vector;

import com.knowagent.common.tenant.TenantId;

import java.util.UUID;

public record VectorChunk(
        TenantId tenantId,
        UUID knowledgeBaseId,
        UUID fileId,
        UUID chunkId,
        String content,
        float[] embedding
) {

    public VectorChunk {
        embedding = embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}

