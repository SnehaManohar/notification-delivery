package com.notifications.model;

/**
 * Supported delivery channels. Adding a new channel means adding a value here,
 * a new NotificationSender implementation, and registering it in the sender factory -
 * the orchestration flow itself does not change.
 */
public enum Channel {
    EMAIL,
    SMS,
    PUSH
}
