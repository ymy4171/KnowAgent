package com.knowagent.knowledge.knowledgebase;

import java.util.List;

/**
 * A single page of knowledge bases plus the total count for the same filter. The
 * caller (application service) always derives the tenant id from the authenticated
 * principal.
 */
public record KnowledgeBasePage(List<KnowledgeBase> knowledgeBases, long total) {

    public KnowledgeBasePage {
        knowledgeBases = List.copyOf(knowledgeBases);
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
    }
}
