package com.knowagent.knowledge.application.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Semantic retrieval response before HTTP mapping. */
public record KnowledgeRetrievalResult(UUID knowledgeBaseId, List<KnowledgeCitation> citations) {

    public KnowledgeRetrievalResult {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        citations = List.copyOf(Objects.requireNonNull(citations, "citations must not be null"));
    }
}
