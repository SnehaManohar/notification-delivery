package com.notifications.dto;

import com.notifications.entity.NotificationDelivery;
import java.time.Instant;

public record DeliveryResponse(
        String deliveryId,
        String channel,
        String status,
        int attemptCount,
        String lastError,
        Instant nextAttemptAt) {

    public static DeliveryResponse from(NotificationDelivery delivery) {
        return new DeliveryResponse(
                delivery.getId(),
                delivery.getChannel().name(),
                delivery.getStatus().name(),
                delivery.getAttemptCount(),
                delivery.getLastError(),
                delivery.getNextAttemptAt());
    }
}
