package com.knowagent.observability.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxStatusTest {

    @Test
    void terminalStatesAreExplicit() {
        assertThat(OutboxStatus.PUBLISHED.isTerminal()).isTrue();
        assertThat(OutboxStatus.DEAD_LETTER.isTerminal()).isTrue();
        assertThat(OutboxStatus.PENDING.isTerminal()).isFalse();
        assertThat(OutboxStatus.PROCESSING.isTerminal()).isFalse();
    }
}
