package com.notifications.dlq;

import com.notifications.dispatch.DeliveryPublisher;
import com.notifications.entity.DeadLetterEntry;
import com.notifications.entity.NotificationDelivery;
import com.notifications.model.DeliveryStatus;
import com.notifications.repository.DeadLetterEntryRepository;
import com.notifications.repository.NotificationDeliveryRepository;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records deliveries that will never be attempted again (retry budget exhausted, or a
 * permanent failure) so an operator can inspect and, if useful, replay them without touching
 * the normal delivery pipeline.
 *
 * <p>{@link DeliveryPublisher} is injected lazily to break a construction-time cycle: Publisher
 * depends on DeliveryDispatchService, which depends on this service (to record failures), which
 * in turn needs the Publisher (to republish on replay). The cycle is real and by-design - a
 * lazy proxy just defers resolving it past bean construction, since {@code replay} is only
 * ever called well after the context has finished starting.
 */
@Service
public class DlqService {

    private final DeadLetterEntryRepository dlqRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final DeliveryPublisher publisher;

    public DlqService(
            DeadLetterEntryRepository dlqRepository,
            NotificationDeliveryRepository deliveryRepository,
            @Lazy DeliveryPublisher publisher) {
        this.dlqRepository = dlqRepository;
        this.deliveryRepository = deliveryRepository;
        this.publisher = publisher;
    }

    public void record(NotificationDelivery delivery, String failureReason) {
        DeadLetterEntry entry =
                DeadLetterEntry.builder()
                        .deliveryId(delivery.getId())
                        .notificationId(delivery.getNotificationId())
                        .userId(delivery.getUserId())
                        .channel(delivery.getChannel())
                        .failureReason(failureReason)
                        .attemptCount(delivery.getAttemptCount())
                        .createdAt(Instant.now())
                        .build();
        dlqRepository.save(entry);
    }

    public List<DeadLetterEntry> listAll() {
        return dlqRepository.findAll();
    }

    /**
     * Operator-triggered replay: resets the delivery's attempt/backoff state and re-publishes
     * it through the normal dispatch pipeline. The DLQ entry is left in place as a historical
     * record of the original failure.
     */
    @Transactional
    public void replay(String deliveryId) {
        NotificationDelivery delivery =
                deliveryRepository
                        .findById(deliveryId)
                        .orElseThrow(() -> new NoSuchElementException("No delivery found for id " + deliveryId));

        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(null);
        delivery.setLastError(null);
        delivery.setUpdatedAt(Instant.now());
        deliveryRepository.save(delivery);

        publisher.publish(delivery.getId());
    }
}
