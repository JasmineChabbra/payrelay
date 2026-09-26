package com.payrelay.api.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffPolicyTest {

    private final Duration base = Duration.ofSeconds(1);
    private final Duration max = Duration.ofSeconds(60);

    @Test
    void doublesEachAttemptWithoutJitter() {
        var policy = new BackoffPolicy(base, max, () -> 1.0); // jitter factor 1.0 = full delay
        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void isCappedAtMaxDelay() {
        var policy = new BackoffPolicy(base, max, () -> 1.0);
        assertThat(policy.delayAfter(10)).isEqualTo(max);
        assertThat(policy.delayAfter(1000)).isEqualTo(max); // no overflow
    }

    @Test
    void jitterKeepsDelayBetweenHalfAndFull() {
        var low = new BackoffPolicy(base, max, () -> 0.0);
        assertThat(low.delayAfter(3)).isEqualTo(Duration.ofSeconds(2)); // 50% of 4s
    }
}
