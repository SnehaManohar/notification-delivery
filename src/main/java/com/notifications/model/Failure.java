package com.notifications.model;

/**
 * Describes why a send attempt failed, classified up front by the sender that raised it.
 * RetryPolicy uses {@code retryable} to decide whether another attempt is worthwhile at all;
 * it never has to guess from an exception type.
 */
public record Failure(boolean retryable, String reason) {

    public static Failure retryable(String reason) {
        return new Failure(true, reason);
    }

    public static Failure nonRetryable(String reason) {
        return new Failure(false, reason);
    }
}
