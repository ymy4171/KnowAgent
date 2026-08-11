package com.knowagent.agent.run;

import com.knowagent.agent.event.RunEvent;
import com.knowagent.common.tenant.TenantId;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOrchestratorContractTest {

    @Test
    void streamsEventsInExecutionOrder() {
        var context = context();
        var started = event(context.runId(), RunEvent.Type.RUN_STARTED, null);
        var delta = event(context.runId(), RunEvent.Type.MODEL_DELTA, "hello");
        var completed = event(context.runId(), RunEvent.Type.RUN_COMPLETED, "hello");
        AgentOrchestrator orchestrator = ignored -> Flux.just(started, delta, completed);

        StepVerifier.create(orchestrator.execute(context))
                .expectNext(started, delta, completed)
                .verifyComplete();
    }

    @Test
    void propagatesSubscriberCancellation() {
        var cancelled = new AtomicBoolean();
        AgentOrchestrator orchestrator = ignored -> Flux.<RunEvent>never().doOnCancel(() -> cancelled.set(true));

        StepVerifier.create(orchestrator.execute(context()))
                .thenCancel()
                .verify();

        assertThat(cancelled).isTrue();
    }

    private static AgentRunContext context() {
        return new AgentRunContext(
                TenantId.of(UUID.randomUUID()),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "question"
        );
    }

    private static RunEvent event(UUID runId, RunEvent.Type type, String data) {
        return new RunEvent(UUID.randomUUID(), runId, type, data, Map.of(), Instant.now());
    }
}
