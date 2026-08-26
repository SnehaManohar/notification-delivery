package com.notifications.model;

/**
 * Aggregate status of a Notification, derived from the status of its individual
 * NotificationDelivery records. Never stored as an independent source of truth -
 * always computed from the deliveries so it can't drift out of sync.
 */
public enum NotificationStatus {
    ACCEPTED,
    IN_PROGRESS,
    PARTIALLY_DELIVERED,
    DELIVERED,
    FAILED
}
