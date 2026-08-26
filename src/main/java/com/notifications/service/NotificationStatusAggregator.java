package com.notifications.service;

import com.notifications.entity.NotificationDelivery;
import com.notifications.model.DeliveryStatus;
import com.notifications.model.NotificationStatus;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Notification status is never stored - it's always derived from the current state of its
 * deliveries, so the two can never drift out of sync.
 */
@Component
public class NotificationStatusAggregator {

    public NotificationStatus aggregate(List<NotificationDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            return NotificationStatus.ACCEPTED;
        }

        boolean allTerminal = deliveries.stream().allMatch(this::isTerminal);
        long delivered = deliveries.stream().filter(d -> d.getStatus() == DeliveryStatus.DELIVERED).count();

        if (!allTerminal) {
            return NotificationStatus.IN_PROGRESS;
        }
        if (delivered == deliveries.size()) {
            return NotificationStatus.DELIVERED;
        }
        if (delivered == 0) {
            return NotificationStatus.FAILED;
        }
        return NotificationStatus.PARTIALLY_DELIVERED;
    }

    private boolean isTerminal(NotificationDelivery delivery) {
        DeliveryStatus status = delivery.getStatus();
        return status == DeliveryStatus.DELIVERED
                || status == DeliveryStatus.FAILED
                || status == DeliveryStatus.EXHAUSTED;
    }
}
