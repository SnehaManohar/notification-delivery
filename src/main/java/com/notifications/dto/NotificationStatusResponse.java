package com.notifications.dto;

import java.util.List;

public record NotificationStatusResponse(
        String notificationId, String userId, String type, String status, List<DeliveryResponse> deliveries) {}
