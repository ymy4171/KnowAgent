package com.knowagent.knowledge.chunk;

public record ChunkPolicy(
        Strategy strategy,
        int maxTokens,
        int overlapTokens
) {

    public enum Strategy {
        RECURSIVE,
        MARKDOWN_HEADING,
        TOKEN_WINDOW
    }
}

