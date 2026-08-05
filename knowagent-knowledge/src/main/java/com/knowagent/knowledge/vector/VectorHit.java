package com.knowagent.knowledge.vector;

import java.util.UUID;

public record VectorHit(
        UUID chunkId,
        UUID fileId,
        String content,
        double score
) {
}

