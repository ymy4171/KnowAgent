package com.knowagent.api.knowledgebase.dto;

import java.util.List;

/** Paged knowledge-base listing plus the total for the same filter. */
public record KnowledgeBasePageResponse(List<KnowledgeBaseResponse> items, long total, int page, int size) {
}
