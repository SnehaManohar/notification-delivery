package com.notifications.decorator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notifications.entity.NotificationDelivery;
import com.notifications.model.Channel;
import com.notifications.ratelimit.RateLimitKey;
import com.notifications.ratelimit.RateLimiter;
import com.notifications.sender.NotificationSender;
import com.notifications.sender.SendResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RateLimitedSenderTest {

    @Mock private NotificationSender delegate;
    @Mock private RateLimiter rateLimiter;

    @Test
    void forwardsToDelegate_whenBothBucketsAllow() {
        NotificationDelivery delivery = delivery();
        when(rateLimiter.allow(any(RateLimitKey.class))).thenReturn(true);
        when(delegate.send(delivery)).thenReturn(SendResult.success("id-1"));

        RateLimitedSender sender = new RateLimitedSender(delegate, rateLimiter);
        SendResult result = sender.send(delivery);

        assertThat(result.providerMessageId()).isEqualTo("id-1");
        verify(delegate).send(delivery);
    }

    @Test
    void throwsAndNeverCallsDelegate_whenUserBucketRejects() {
        NotificationDelivery delivery = delivery();
        when(rateLimiter.allow(RateLimitKey.forUser("u1"))).thenReturn(false);

        RateLimitedSender sender = new RateLimitedSender(delegate, rateLimiter);

        assertThatThrownBy(() -> sender.send(delivery)).isInstanceOf(RateLimitExceededException.class);
        verify(delegate, never()).send(any());
    }

    @Test
    void throwsAndNeverCallsDelegate_whenChannelBucketRejects() {
        NotificationDelivery delivery = delivery();
        when(rateLimiter.allow(RateLimitKey.forUser("u1"))).thenReturn(true);
        when(rateLimiter.allow(RateLimitKey.forChannel(Channel.EMAIL))).thenReturn(false);

        RateLimitedSender sender = new RateLimitedSender(delegate, rateLimiter);

        assertThatThrownBy(() -> sender.send(delivery)).isInstanceOf(RateLimitExceededException.class);
        verify(delegate, never()).send(any());
    }

    private NotificationDelivery delivery() {
        return NotificationDelivery.builder()
                .id("d1")
                .notificationId("n1")
                .userId("u1")
                .notificationType("ORDER_SHIPPED")
                .channel(Channel.EMAIL)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
