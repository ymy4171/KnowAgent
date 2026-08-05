package com.knowagent.agent.checkpoint;

import java.time.Instant;
import java.util.UUID;

public record AgentCheckpoint(
        UUID runId,
        String stage,
        String payload,
        Instant createdAt
) {
}

