package com.knowagent.agent.event;

import java.util.Objects;

public record PublishedRunEvent(
        String cursor,
        RunEvent event
) {

    public PublishedRunEvent {
        Objects.requireNonNull(cursor, "cursor must not be null");
        Objects.requireNonNull(event, "event must not be null");
        if (cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must not be blank");
        }
    }
}
