package com.notifications.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notifications.entity.NotificationDelivery;
import com.notifications.model.Channel;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code simulate} payload contract shared by every channel sender - verified
 * here against EmailSender; SmsSender and PushSender delegate to the identical helper.
 */
class EmailSenderTest {

    private final EmailSender sender = new EmailSender();

    @Test
    void succeeds_whenNoSimulateKeyPresent() {
        SendResult result = sender.send(delivery(0, Map.of()));
        assertThat(result.providerMessageId()).startsWith("email-");
    }

    @Test
    void succeeds_whenSimulateIsExplicitSuccess() {
        SendResult result = sender.send(delivery(0, Map.of("simulate", "SUCCESS")));
        assertThat(result.providerMessageId()).isNotBlank();
    }

    @Test
    void throwsNonRetryable_forPermanentFailure() {
        assertThatThrownBy(() -> sender.send(delivery(0, Map.of("simulate", "PERMANENT_FAILURE"))))
                .isInstanceOf(NonRetryableSendException.class);
    }

    @Test
    void throwsRetryable_forRetryableFailure_regardlessOfAttemptCount() {
        assertThatThrownBy(() -> sender.send(delivery(0, Map.of("simulate", "RETRYABLE_FAILURE"))))
                .isInstanceOf(RetryableSendException.class);
        assertThatThrownBy(() -> sender.send(delivery(5, Map.of("simulate", "RETRYABLE_FAILURE"))))
                .isInstanceOf(RetryableSendException.class);
    }

    @Test
    void failOnce_failsFirstAttempt_thenSucceeds() {
        Map<String, String> payload = Map.of("simulate", "FAIL_ONCE");

        assertThatThrownBy(() -> sender.send(delivery(0, payload))).isInstanceOf(RetryableSendException.class);

        SendResult result = sender.send(delivery(1, payload));
        assertThat(result.providerMessageId()).isNotBlank();
    }

    @Test
    void failTwice_failsFirstTwoAttempts_thenSucceeds() {
        Map<String, String> payload = Map.of("simulate", "FAIL_TWICE");

        assertThatThrownBy(() -> sender.send(delivery(0, payload))).isInstanceOf(RetryableSendException.class);
        assertThatThrownBy(() -> sender.send(delivery(1, payload))).isInstanceOf(RetryableSendException.class);

        SendResult result = sender.send(delivery(2, payload));
        assertThat(result.providerMessageId()).isNotBlank();
    }

    private NotificationDelivery delivery(int attemptCount, Map<String, String> payload) {
        return NotificationDelivery.builder()
                .id("d1")
                .notificationId("n1")
                .userId("u1")
                .notificationType("ORDER_SHIPPED")
                .channel(Channel.EMAIL)
                .attemptCount(attemptCount)
                .payload(new HashMap<>(payload))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
