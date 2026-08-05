package com.knowagent.agent.checkpoint;

import java.util.Optional;
import java.util.UUID;

public interface CheckpointStore {

    Optional<AgentCheckpoint> load(UUID runId);

    void save(AgentCheckpoint checkpoint);
}

