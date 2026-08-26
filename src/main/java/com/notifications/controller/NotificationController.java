package com.notifications.controller;

import com.notifications.dto.DeliveryResponse;
import com.notifications.dto.NotificationCreatedResponse;
import com.notifications.dto.NotificationRequest;
import com.notifications.dto.NotificationStatusResponse;
import com.notifications.entity.Notification;
import com.notifications.entity.NotificationDelivery;
import com.notifications.service.NotificationService;
import com.notifications.service.NotificationStatusAggregator;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationStatusAggregator statusAggregator;

    public NotificationController(
            NotificationService notificationService, NotificationStatusAggregator statusAggregator) {
        this.notificationService = notificationService;
        this.statusAggregator = statusAggregator;
    }

    /**
     * Accepts a logical notification. Delivery is asynchronous - this endpoint only confirms
     * that the notification (and its resolved deliveries) were durably persisted.
     */
    @PostMapping
    public ResponseEntity<NotificationCreatedResponse> create(@Valid @RequestBody NotificationRequest request) {
        Notification notification = notificationService.createNotification(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new NotificationCreatedResponse(notification.getId(), "ACCEPTED"));
    }

    @GetMapping("/{notificationId}")
    public NotificationStatusResponse get(@PathVariable String notificationId) {
        Notification notification = notificationService.getNotification(notificationId);
        List<NotificationDelivery> deliveries = notificationService.getDeliveries(notificationId);

        List<DeliveryResponse> deliveryResponses = deliveries.stream().map(DeliveryResponse::from).toList();

        return new NotificationStatusResponse(
                notification.getId(),
                notification.getUserId(),
                notification.getType(),
                statusAggregator.aggregate(deliveries).name(),
                deliveryResponses);
    }
}
