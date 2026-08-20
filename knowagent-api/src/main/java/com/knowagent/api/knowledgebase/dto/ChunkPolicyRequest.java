package com.knowagent.api.knowledgebase.dto;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.chunk.ChunkPolicy;

/**
 * HTTP representation of a chunking policy. {@link #toDomain()} enforces the policy
 * invariant ({@code maxTokens > 0}, {@code overlapTokens >= 0}, {@code overlapTokens <
 * maxTokens}) and surfaces a violation as a stable 400 rather than an internal error.
 */
public record ChunkPolicyRequest(
        ChunkPolicy.Strategy strategy,
        int maxTokens,
        int overlapTokens
) {
    public ChunkPolicy toDomain() {
        try {
            return new ChunkPolicy(strategy, maxTokens, overlapTokens);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, exception.getMessage());
        }
    }
}
