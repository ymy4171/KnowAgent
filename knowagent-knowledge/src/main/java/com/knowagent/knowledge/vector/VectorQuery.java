package com.knowagent.knowledge.vector;

import com.knowagent.common.tenant.TenantId;

import java.util.UUID;

public record VectorQuery(
        TenantId tenantId,
        UUID knowledgeBaseId,
        float[] embedding,
        int topK,
        double minimumScore
) {

    public VectorQuery {
        embedding = embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}

