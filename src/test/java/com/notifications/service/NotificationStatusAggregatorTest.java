package com.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.notifications.entity.NotificationDelivery;
import com.notifications.model.Channel;
import com.notifications.model.DeliveryStatus;
import com.notifications.model.NotificationStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationStatusAggregatorTest {

    private final NotificationStatusAggregator aggregator = new NotificationStatusAggregator();

    @Test
    void inProgress_whenAnyDeliveryStillPending() {
        List<NotificationDelivery> deliveries =
                List.of(delivery(DeliveryStatus.DELIVERED), delivery(DeliveryStatus.RETRYING));

        assertThat(aggregator.aggregate(deliveries)).isEqualTo(NotificationStatus.IN_PROGRESS);
    }

    @Test
    void delivered_whenAllDeliveriesSucceeded() {
        List<NotificationDelivery> deliveries =
                List.of(delivery(DeliveryStatus.DELIVERED), delivery(DeliveryStatus.DELIVERED));

        assertThat(aggregator.aggregate(deliveries)).isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    void partiallyDelivered_whenMixOfSuccessAndTerminalFailure() {
        List<NotificationDelivery> deliveries =
                List.of(delivery(DeliveryStatus.DELIVERED), delivery(DeliveryStatus.EXHAUSTED));

        assertThat(aggregator.aggregate(deliveries)).isEqualTo(NotificationStatus.PARTIALLY_DELIVERED);
    }

    @Test
    void failed_whenAllDeliveriesTerminallyFailed() {
        List<NotificationDelivery> deliveries =
                List.of(delivery(DeliveryStatus.FAILED), delivery(DeliveryStatus.EXHAUSTED));

        assertThat(aggregator.aggregate(deliveries)).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void accepted_whenNoDeliveriesExist() {
        assertThat(aggregator.aggregate(List.of())).isEqualTo(NotificationStatus.ACCEPTED);
    }

    private NotificationDelivery delivery(DeliveryStatus status) {
        Instant now = Instant.now();
        return NotificationDelivery.builder()
                .id("d-" + status)
                .notificationId("n1")
                .userId("u1")
                .notificationType("ORDER_SHIPPED")
                .channel(Channel.EMAIL)
                .status(status)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
