package com.notifications.entity;

import com.notifications.model.Channel;
import com.notifications.model.DeliveryStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single channel delivery attempt belonging to a Notification. Each delivery owns its own
 * status/attempt/backoff state so one channel can succeed while another retries or fails,
 * without affecting the others (fault isolation between channels).
 */
@Entity
@Table(
        name = "notification_deliveries",
        indexes = {
            @Index(name = "idx_delivery_notification_id", columnList = "notification_id"),
            @Index(name = "idx_delivery_status", columnList = "status")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDelivery {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "notification_id", nullable = false)
    private String notificationId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "notification_type", nullable = false)
    private String notificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeliveryStatus status;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    /**
     * Denormalized snapshot of the payload at creation time, so a worker can send this
     * delivery without needing to re-fetch and re-render the parent Notification.
     */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "delivery_payload", joinColumns = @JoinColumn(name = "delivery_id"))
    @MapKeyColumn(name = "payload_key")
    @Column(name = "payload_value", length = 2000)
    private Map<String, String> payload = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
