package com.notifications.decorator;

/**
 * Raised by {@link RateLimitedSender} when neither the user nor the channel bucket has a token
 * available. Deliberately not a {@code SendException}: a rate-limit rejection isn't a failed
 * provider attempt, so it must not consume a retry attempt. The dispatcher catches this
 * separately and reschedules the delivery a short, fixed delay later instead of applying the
 * RetryPolicy's backoff.
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
