package com.knowagent.common.event;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {

    UUID eventId();

    String aggregateId();

    Instant occurredAt();
}

