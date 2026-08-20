package com.knowagent.api.knowledgebase.dto;

import java.util.List;

/** Paged knowledge-file listing plus the total for the same filter. */
public record KnowledgeFilePageResponse(List<KnowledgeFileResponse> items, long total, int page, int size) {
}
