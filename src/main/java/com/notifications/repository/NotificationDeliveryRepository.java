package com.notifications.repository;

import com.notifications.entity.NotificationDelivery;
import com.notifications.model.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, String> {

    List<NotificationDelivery> findByNotificationId(String notificationId);

    List<NotificationDelivery> findByStatus(DeliveryStatus status);

    List<NotificationDelivery> findByStatusAndNextAttemptAtLessThanEqual(
            DeliveryStatus status, Instant threshold);

    List<NotificationDelivery> findByStatusAndCreatedAtLessThanEqual(
            DeliveryStatus status, Instant threshold);
}
