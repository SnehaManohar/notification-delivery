package com.notifications.retry;

import static org.assertj.core.api.Assertions.assertThat;

import com.notifications.model.Failure;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExponentialBackoffRetryPolicyTest {

    private final ExponentialBackoffRetryPolicy policy =
            new ExponentialBackoffRetryPolicy(3, Duration.ofMillis(100), 2.0, Duration.ofSeconds(10));

    @Test
    void shouldRetry_true_whenRetryableAndUnderBudget() {
        assertThat(policy.shouldRetry(1, Failure.retryable("timeout"))).isTrue();
        assertThat(policy.shouldRetry(2, Failure.retryable("timeout"))).isTrue();
    }

    @Test
    void shouldRetry_false_onceMaxRetriesReached() {
        assertThat(policy.shouldRetry(3, Failure.retryable("timeout"))).isFalse();
        assertThat(policy.shouldRetry(4, Failure.retryable("timeout"))).isFalse();
    }

    @Test
    void shouldRetry_false_forNonRetryableFailure_regardlessOfAttempt() {
        assertThat(policy.shouldRetry(1, Failure.nonRetryable("invalid address"))).isFalse();
    }

    @Test
    void nextBackoff_growsExponentiallyThenCaps() {
        assertThat(policy.nextBackoff(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.nextBackoff(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.nextBackoff(3)).isEqualTo(Duration.ofMillis(400));

        ExponentialBackoffRetryPolicy tightlyCapped =
                new ExponentialBackoffRetryPolicy(10, Duration.ofMillis(100), 2.0, Duration.ofMillis(300));
        assertThat(tightlyCapped.nextBackoff(5)).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    void fixedBackoff_whenMultiplierIsOne() {
        ExponentialBackoffRetryPolicy fixed =
                new ExponentialBackoffRetryPolicy(5, Duration.ofMillis(500), 1.0, Duration.ofSeconds(5));
        assertThat(fixed.nextBackoff(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(fixed.nextBackoff(4)).isEqualTo(Duration.ofMillis(500));
    }
}
