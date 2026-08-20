package com.knowagent.observability.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusTest {

    @Test
    void terminalStatesAreExplicit() {
        assertThat(TaskStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(TaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(TaskStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(TaskStatus.PENDING.isTerminal()).isFalse();
        assertThat(TaskStatus.RUNNING.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING, RUNNING",
            "PENDING, FAILED",
            "PENDING, CANCELLED",
            "RUNNING, SUCCEEDED",
            "RUNNING, FAILED",
            "RUNNING, PENDING",
            "RUNNING, CANCELLED"
    })
    void legalTransitionsAreAllowed(TaskStatus source, TaskStatus target) {
        assertThat(source.canTransitionTo(target)).as("%s -> %s", source, target).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING, SUCCEEDED",
            "PENDING, PENDING",
            "RUNNING, RUNNING",
            "SUCCEEDED, PENDING",
            "SUCCEEDED, FAILED",
            "SUCCEEDED, SUCCEEDED",
            "FAILED, FAILED",
            "FAILED, CANCELLED",
            "CANCELLED, PENDING",
            "CANCELLED, RUNNING"
    })
    void illegalTransitionsAreRejected(TaskStatus source, TaskStatus target) {
        assertThat(source.canTransitionTo(target)).as("%s -> %s", source, target).isFalse();
    }

    @Test
    void terminalStatesNeverTransition() {
        for (TaskStatus terminal : new TaskStatus[]{TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED}) {
            for (TaskStatus target : TaskStatus.values()) {
                assertThat(terminal.canTransitionTo(target))
                        .as("%s -> %s", terminal, target)
                        .isFalse();
            }
        }
    }

    @Test
    void nullTargetIsRejected() {
        assertThat(TaskStatus.PENDING.canTransitionTo(null)).isFalse();
        assertThat(TaskStatus.RUNNING.canTransitionTo(null)).isFalse();
    }
}
