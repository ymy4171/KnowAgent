package com.knowagent.api.knowledgebase.dto;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.knowledgebase.RetrievalConfig;

/**
 * HTTP representation of a retrieval configuration. {@link #toDomain()} enforces the
 * value bounds ({@code topK} within {@code [1,100]}, {@code scoreThreshold} within
 * {@code [0,1]}) and surfaces a violation as a stable 400.
 */
public record RetrievalConfigRequest(
        int topK,
        double scoreThreshold,
        boolean rerankEnabled
) {
    public RetrievalConfig toDomain() {
        try {
            return new RetrievalConfig(topK, scoreThreshold, rerankEnabled);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, exception.getMessage());
        }
    }
}
