package com.notifications.retry;

import com.notifications.model.Failure;
import java.time.Duration;

/**
 * Retry is configurable and channel-specific rather than hardcoded into any sender, because
 * different provider characteristics (SMS vendors vs. email vendors) warrant different retry
 * budgets and backoff curves.
 */
public interface RetryPolicy {

    boolean shouldRetry(int attempt, Failure failure);

    Duration nextBackoff(int attempt);
}
