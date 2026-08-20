package com.knowagent.api.knowledgebase.dto;

import com.knowagent.knowledge.application.service.KnowledgeCitation;

import java.util.List;
import java.util.UUID;

/** Public citation fields backed by the final PostgreSQL hydration. */
public record KnowledgeCitationResponse(
        UUID chunkId,
        UUID fileId,
        String displayName,
        String content,
        Integer pageNumber,
        List<String> sectionPath,
        double score,
        int rank) {

    public KnowledgeCitationResponse {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
    }

    public static KnowledgeCitationResponse from(KnowledgeCitation citation) {
        return new KnowledgeCitationResponse(citation.chunkId(), citation.fileId(), citation.displayName(),
                citation.content(), citation.pageNumber(), citation.sectionPath(), citation.score(), citation.rank());
    }

    @Override
    public String toString() {
        return "KnowledgeCitationResponse[chunkId=" + chunkId
                + ", fileId=" + fileId
                + ", displayName=" + displayName
                + ", pageNumber=" + pageNumber
                + ", sectionPath=" + sectionPath
                + ", score=" + score
                + ", rank=" + rank + "]";
    }
}
