package com.knowagent.api.knowledgebase.dto;

import com.knowagent.knowledge.chunk.ChunkPolicy;

/** Public view of a knowledge base's chunking policy. */
public record ChunkPolicyResponse(
        ChunkPolicy.Strategy strategy,
        int maxTokens,
        int overlapTokens
) {
    public static ChunkPolicyResponse from(ChunkPolicy policy) {
        return new ChunkPolicyResponse(policy.strategy(), policy.maxTokens(), policy.overlapTokens());
    }
}
