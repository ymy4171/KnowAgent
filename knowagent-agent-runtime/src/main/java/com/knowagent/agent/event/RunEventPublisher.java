package com.knowagent.agent.event;

import reactor.core.publisher.Flux;

import java.util.UUID;

public interface RunEventPublisher {

    String publish(RunEvent event);

    Flux<RunEvent> replay(UUID runId, String lastEventId);
}

