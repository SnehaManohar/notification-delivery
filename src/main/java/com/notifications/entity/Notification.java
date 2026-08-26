package com.notifications.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
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
 * A logical notification request. One Notification fans out into one NotificationDelivery
 * per resolved channel - delivery state (success/retry/failure) intentionally lives on the
 * child records, not here, since each channel progresses independently.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    /**
     * Notification type is a plain string (e.g. "ORDER_SHIPPED", "PASSWORD_RESET") rather than
     * a compiled enum, because routing rules and new notification types are data-driven via
     * UserPreference and shouldn't require a code change/redeploy to introduce.
     */
    @Column(name = "type", nullable = false)
    private String type;

    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "notification_payload", joinColumns = @JoinColumn(name = "notification_id"))
    @MapKeyColumn(name = "payload_key")
    @Column(name = "payload_value", length = 2000)
    private Map<String, String> payload = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
