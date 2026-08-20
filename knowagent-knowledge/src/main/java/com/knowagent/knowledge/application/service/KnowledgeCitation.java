package com.knowagent.knowledge.application.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A citation hydrated exclusively from PostgreSQL after vector candidate search. */
public record KnowledgeCitation(
        UUID chunkId,
        UUID fileId,
        String displayName,
        String content,
        Integer pageNumber,
        List<String> sectionPath,
        double score,
        int rank) {

    public KnowledgeCitation {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(content, "content must not be null");
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
    }

    @Override
    public String toString() {
        return "KnowledgeCitation[chunkId=" + chunkId + ", fileId=" + fileId
                + ", pageNumber=" + pageNumber + ", score=" + score + ", rank=" + rank + "]";
    }
}
