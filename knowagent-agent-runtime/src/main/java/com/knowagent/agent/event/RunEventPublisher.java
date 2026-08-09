package com.knowagent.agent.event;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RunEventPublisher {

    Mono<PublishedRunEvent> publish(RunEvent event);

    Flux<PublishedRunEvent> replay(UUID runId, String lastEventId);
}
