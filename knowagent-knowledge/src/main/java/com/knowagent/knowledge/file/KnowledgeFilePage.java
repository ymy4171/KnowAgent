package com.knowagent.knowledge.file;

import java.util.List;

/**
 * A single page of knowledge files plus the total count for the same filter. The
 * caller (application service) always derives the tenant id from the authenticated
 * principal.
 */
public record KnowledgeFilePage(List<KnowledgeFile> files, long total) {

    public KnowledgeFilePage {
        files = List.copyOf(files);
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
    }
}
