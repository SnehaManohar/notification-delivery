package com.notifications.dispatch;

import com.notifications.entity.NotificationDelivery;
import com.notifications.model.DeliveryStatus;
import com.notifications.repository.NotificationDeliveryRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The reliable-publishing half of at-least-once delivery. Two situations are swept up here:
 *
 * <ul>
 *   <li>RETRYING deliveries whose backoff (or rate-limit cool-down) has elapsed - republished
 *       so the next attempt actually happens.</li>
 *   <li>PENDING deliveries stuck older than a threshold - covers the case where a delivery was
 *       persisted but the in-memory publish step never happened (e.g. the process restarted
 *       between persist and publish). In a real deployment this is where an outbox-poller or
 *       broker redelivery would take over; here the same scheduler plays both roles.</li>
 * </ul>
 */
@Component
public class DeliveryReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryReconciliationScheduler.class);

    private final NotificationDeliveryRepository deliveryRepository;
    private final DeliveryPublisher publisher;
    private final long stuckPendingThresholdMillis;

    public DeliveryReconciliationScheduler(
            NotificationDeliveryRepository deliveryRepository,
            DeliveryPublisher publisher,
            @Value("${notification.dispatch.stuck-pending-threshold-millis:5000}") long stuckPendingThresholdMillis) {
        this.deliveryRepository = deliveryRepository;
        this.publisher = publisher;
        this.stuckPendingThresholdMillis = stuckPendingThresholdMillis;
    }

    @Scheduled(fixedDelayString = "${notification.dispatch.reconciliation-interval-millis:500}")
    public void reconcile() {
        Instant now = Instant.now();

        List<NotificationDelivery> due =
                deliveryRepository.findByStatusAndNextAttemptAtLessThanEqual(DeliveryStatus.RETRYING, now);
        due.forEach(delivery -> publisher.publish(delivery.getId()));

        List<NotificationDelivery> stuckPending =
                deliveryRepository.findByStatusAndCreatedAtLessThanEqual(
                        DeliveryStatus.PENDING, now.minusMillis(stuckPendingThresholdMillis));
        if (!stuckPending.isEmpty()) {
            log.info("Republishing {} stuck PENDING deliveries", stuckPending.size());
            stuckPending.forEach(delivery -> publisher.publish(delivery.getId()));
        }
    }
}
