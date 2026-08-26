package com.notifications.sender;

/** Successful outcome of a NotificationSender#send call. Failures are raised as exceptions. */
public record SendResult(String providerMessageId, String providerMessage) {

    public static SendResult success(String providerMessageId) {
        return new SendResult(providerMessageId, "accepted by provider");
    }
}
