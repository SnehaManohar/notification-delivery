package com.notifications.retry;

import com.notifications.model.Failure;
import java.time.Duration;

/**
 * shouldRetry first checks the failure's own retryable classification - a permanent failure
 * (invalid address, malformed request) is never retried regardless of attempt count, since
 * retrying it only wastes capacity and can amplify the underlying problem.
 */
public class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final int maxRetries;
    private final Duration initialBackoff;
    private final double multiplier;
    private final Duration maxBackoff;

    public ExponentialBackoffRetryPolicy(
            int maxRetries, Duration initialBackoff, double multiplier, Duration maxBackoff) {
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
        this.multiplier = multiplier;
        this.maxBackoff = maxBackoff;
    }

    @Override
    public boolean shouldRetry(int attempt, Failure failure) {
        return failure.retryable() && attempt < maxRetries;
    }

    @Override
    public Duration nextBackoff(int attempt) {
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, Math.max(0, attempt - 1));
        long capped = Math.min((long) millis, maxBackoff.toMillis());
        return Duration.ofMillis(capped);
    }
}
