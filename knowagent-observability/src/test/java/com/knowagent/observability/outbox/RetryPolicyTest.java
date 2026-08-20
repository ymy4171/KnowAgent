package com.knowagent.observability.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

    @Test
    void delayDoublesEachAttempt() {
        RetryPolicy policy = new RetryPolicy(1000, 60000);
        assertThat(policy.nextRetryAt(NOW, 1)).isEqualTo(NOW.plusSeconds(1));
        assertThat(policy.nextRetryAt(NOW, 2)).isEqualTo(NOW.plusSeconds(2));
        assertThat(policy.nextRetryAt(NOW, 3)).isEqualTo(NOW.plusSeconds(4));
        assertThat(policy.nextRetryAt(NOW, 4)).isEqualTo(NOW.plusSeconds(8));
    }

    @Test
    void delayCapsAtMax() {
        RetryPolicy policy = new RetryPolicy(1000, 5000);
        assertThat(policy.nextRetryAt(NOW, 1)).isEqualTo(NOW.plusSeconds(1));
        assertThat(policy.nextRetryAt(NOW, 2)).isEqualTo(NOW.plusSeconds(2));
        assertThat(policy.nextRetryAt(NOW, 3)).isEqualTo(NOW.plusSeconds(4));
        // Attempt 4 would be 8000ms but is capped at 5000ms.
        assertThat(policy.nextRetryAt(NOW, 4)).isEqualTo(NOW.plusMillis(5000));
        // And stays capped for later attempts.
        assertThat(policy.nextRetryAt(NOW, 10)).isEqualTo(NOW.plusMillis(5000));
    }

    @Test
    void defaultPolicyMatchesDocumentedValues() {
        assertThat(RetryPolicy.DEFAULT.baseDelayMillis()).isEqualTo(Duration.ofSeconds(1).toMillis());
        assertThat(RetryPolicy.DEFAULT.maxDelayMillis()).isEqualTo(Duration.ofMinutes(5).toMillis());
    }

    @Test
    void rejectsNonPositiveBase() {
        assertThatThrownBy(() -> new RetryPolicy(0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMaxBelowBase() {
        assertThatThrownBy(() -> new RetryPolicy(1000, 500))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAttemptBelowOne() {
        assertThatThrownBy(() -> RetryPolicy.DEFAULT.nextRetryAt(NOW, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
