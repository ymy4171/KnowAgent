package com.knowagent.agent.event;

import com.knowagent.common.event.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RunEventTest {

    @Test
    void separatesDomainIdentityFromStreamCursor() {
        var eventId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var event = new RunEvent(
                eventId,
                runId,
                RunEvent.Type.RUN_STARTED,
                null,
                Map.of(),
                Instant.now()
        );
        var published = new PublishedRunEvent("1738752000000-0", event);

        assertThat(event).isInstanceOf(DomainEvent.class);
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.aggregateId()).isEqualTo(runId.toString());
        assertThat(published.cursor()).isEqualTo("1738752000000-0");
        assertThat(published.event()).isSameAs(event);
    }

    @Test
    void copiesMetadataAndRejectsBlankCursor() {
        var metadata = new HashMap<String, String>();
        metadata.put("model", "demo");
        var event = new RunEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                RunEvent.Type.MODEL_DELTA,
                "hello",
                metadata,
                Instant.now()
        );
        metadata.put("late", "mutation");

        assertThat(event.metadata()).containsExactlyEntriesOf(Map.of("model", "demo"));
        assertThatIllegalArgumentException().isThrownBy(() -> new PublishedRunEvent(" ", event));
    }
}
