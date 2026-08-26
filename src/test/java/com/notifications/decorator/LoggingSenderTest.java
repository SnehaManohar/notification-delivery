package com.notifications.decorator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notifications.entity.NotificationDelivery;
import com.notifications.model.Channel;
import com.notifications.sender.NotificationSender;
import com.notifications.sender.RetryableSendException;
import com.notifications.sender.SendResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoggingSenderTest {

    @Mock private NotificationSender delegate;

    @Test
    void passesThroughSuccessResult_unchanged() {
        NotificationDelivery delivery = delivery();
        when(delegate.send(delivery)).thenReturn(SendResult.success("id-1"));

        LoggingSender sender = new LoggingSender(delegate);
        SendResult result = sender.send(delivery);

        assertThat(result.providerMessageId()).isEqualTo("id-1");
        verify(delegate).send(delivery);
    }

    @Test
    void propagatesException_unchanged() {
        NotificationDelivery delivery = delivery();
        when(delegate.send(delivery)).thenThrow(new RetryableSendException("boom"));

        LoggingSender sender = new LoggingSender(delegate);

        assertThatThrownBy(() -> sender.send(delivery))
                .isInstanceOf(RetryableSendException.class)
                .hasMessage("boom");
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
