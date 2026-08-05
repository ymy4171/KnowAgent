package com.knowagent.knowledge.chunk;

import java.util.Map;

public record ChunkDraft(
        int sequence,
        String content,
        int tokenCount,
        Map<String, String> metadata
) {

    public ChunkDraft {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

