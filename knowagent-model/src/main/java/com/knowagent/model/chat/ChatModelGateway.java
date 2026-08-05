package com.knowagent.model.chat;

import reactor.core.publisher.Flux;

public interface ChatModelGateway {

    Flux<ModelEvent> stream(ChatCommand command);
}

