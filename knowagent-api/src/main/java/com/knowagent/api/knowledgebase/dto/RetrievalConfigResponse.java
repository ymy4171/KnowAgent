package com.knowagent.api.knowledgebase.dto;

import com.knowagent.knowledge.knowledgebase.RetrievalConfig;

/** Public view of a knowledge base's retrieval configuration. */
public record RetrievalConfigResponse(
        int topK,
        double scoreThreshold,
        boolean rerankEnabled
) {
    public static RetrievalConfigResponse from(RetrievalConfig config) {
        return new RetrievalConfigResponse(config.topK(), config.scoreThreshold(), config.rerankEnabled());
    }
}
