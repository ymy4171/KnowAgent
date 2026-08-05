package com.knowagent.agent.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStatusTest {

    @Test
    void requestTerminalStatesAreExplicit() {
        assertThat(AgentRequestStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(AgentRequestStatus.REJECTED.isTerminal()).isTrue();
        assertThat(AgentRequestStatus.FAILED.isTerminal()).isTrue();
        assertThat(AgentRequestStatus.QUEUED.isTerminal()).isFalse();
        assertThat(AgentRequestStatus.DISPATCHED.isTerminal()).isFalse();
    }

    @Test
    void runTerminalStatesExcludeInterrupted() {
        assertThat(AgentRunStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(AgentRunStatus.FAILED.isTerminal()).isTrue();
        assertThat(AgentRunStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(AgentRunStatus.INTERRUPTED.isTerminal()).isFalse();
    }
}

