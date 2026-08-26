package com.notifications.service;

import com.notifications.dispatch.DeliveryPublisher;
import com.notifications.dto.NotificationRequest;
import com.notifications.entity.Notification;
import com.notifications.entity.NotificationDelivery;
import com.notifications.model.Channel;
import com.notifications.model.DeliveryStatus;
import com.notifications.repository.NotificationDeliveryRepository;
import com.notifications.repository.NotificationRepository;
import com.notifications.router.NotificationRouter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Thin orchestrator: validates, asks the router where a notification should go, creates one
 * delivery per resolved channel, persists everything, then publishes each delivery for
 * asynchronous processing - and nothing else. It does not send, rate-limit, or retry; those
 * responsibilities live in the dispatch/sender/retry packages.
 *
 * <p>Publishing happens only after the persistence transaction commits (see the
 * TransactionSynchronization below), so a delivery is never handed to a worker before it's
 * durably recorded - if the process crashes between commit and that callback running, the
 * reconciliation scheduler picks up the still-PENDING delivery on its next sweep.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationRouter router;
    private final DeliveryPublisher publisher;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationDeliveryRepository deliveryRepository,
            NotificationRouter router,
            DeliveryPublisher publisher) {
        this.notificationRepository = notificationRepository;
        this.deliveryRepository = deliveryRepository;
        this.router = router;
        this.publisher = publisher;
    }

    @Transactional
    public Notification createNotification(NotificationRequest request) {
        Instant now = Instant.now();

        Notification notification =
                Notification.builder()
                        .id(UUID.randomUUID().toString())
                        .userId(request.userId())
                        .type(request.type())
                        .payload(request.payload() == null ? Map.of() : new HashMap<>(request.payload()))
                        .createdAt(now)
                        .build();
        notificationRepository.save(notification);

        // Preferences are evaluated once, right now, so a later preference change never
        // mutates the routing decision of an already-created notification.
        List<Channel> routes = router.getRoutes(request.userId(), request.type());

        List<NotificationDelivery> deliveries = new ArrayList<>();
        for (Channel channel : routes) {
            NotificationDelivery delivery =
                    NotificationDelivery.builder()
                            .id(UUID.randomUUID().toString())
                            .notificationId(notification.getId())
                            .userId(notification.getUserId())
                            .notificationType(notification.getType())
                            .channel(channel)
                            .status(DeliveryStatus.PENDING)
                            .attemptCount(0)
                            .payload(new HashMap<>(notification.getPayload()))
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
            deliveries.add(delivery);
        }
        deliveryRepository.saveAll(deliveries);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            deliveries.forEach(d -> publisher.publish(d.getId()));
                        }
                    });
        } else {
            deliveries.forEach(d -> publisher.publish(d.getId()));
        }

        return notification;
    }

    @Transactional(readOnly = true)
    public Notification getNotification(String notificationId) {
        return notificationRepository
                .findById(notificationId)
                .orElseThrow(() -> new NoSuchElementException("No notification found for id " + notificationId));
    }

    @Transactional(readOnly = true)
    public List<NotificationDelivery> getDeliveries(String notificationId) {
        return deliveryRepository.findByNotificationId(notificationId);
    }
}
