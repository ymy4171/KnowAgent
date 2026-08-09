package com.knowagent.agent.run;

import com.knowagent.agent.event.RunEvent;
import reactor.core.publisher.Flux;

public interface AgentOrchestrator {

    Flux<RunEvent> execute(AgentRunContext context);
}
