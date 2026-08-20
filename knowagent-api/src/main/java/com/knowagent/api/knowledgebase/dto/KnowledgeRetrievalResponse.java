package com.knowagent.api.knowledgebase.dto;

import com.knowagent.knowledge.application.service.KnowledgeRetrievalResult;

import java.util.List;
import java.util.UUID;

/** Retrieval response without query echo, provider data, object keys or internal metadata. */
public record KnowledgeRetrievalResponse(
        UUID knowledgeBaseId,
        List<KnowledgeCitationResponse> citations) {

    public KnowledgeRetrievalResponse {
        citations = List.copyOf(citations);
    }

    public static KnowledgeRetrievalResponse from(KnowledgeRetrievalResult result) {
        return new KnowledgeRetrievalResponse(result.knowledgeBaseId(), result.citations().stream()
                .map(KnowledgeCitationResponse::from)
                .toList());
    }
}
