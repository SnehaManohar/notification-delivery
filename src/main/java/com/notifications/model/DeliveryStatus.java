package com.notifications.model;

/**
 * Lifecycle state of a single NotificationDelivery (one channel attempt).
 *
 * PENDING      -> persisted, waiting to be published/dispatched
 * DISPATCHING  -> currently being sent to the channel provider
 * RETRYING     -> a retryable failure or rate-limit rejection occurred; will be re-attempted at nextAttemptAt
 * DELIVERED    -> terminal success
 * FAILED       -> terminal, non-retryable failure (sent straight to DLQ)
 * EXHAUSTED    -> terminal, retry budget exhausted (sent to DLQ)
 */
public enum DeliveryStatus {
    PENDING,
    DISPATCHING,
    RETRYING,
    DELIVERED,
    FAILED,
    EXHAUSTED
}
