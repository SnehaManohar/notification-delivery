package com.notifications.dto;

import com.notifications.entity.DeadLetterEntry;
import java.time.Instant;

public record DlqEntryResponse(
        Long id,
        String deliveryId,
        String notificationId,
        String userId,
        String channel,
        String failureReason,
        int attemptCount,
        Instant createdAt) {

    public static DlqEntryResponse from(DeadLetterEntry entry) {
        return new DlqEntryResponse(
                entry.getId(),
                entry.getDeliveryId(),
                entry.getNotificationId(),
                entry.getUserId(),
                entry.getChannel().name(),
                entry.getFailureReason(),
                entry.getAttemptCount(),
                entry.getCreatedAt());
    }
}
