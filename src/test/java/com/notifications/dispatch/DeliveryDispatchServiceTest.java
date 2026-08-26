package com.notifications.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notifications.decorator.RateLimitExceededException;
import com.notifications.dlq.DlqService;
import com.notifications.entity.NotificationDelivery;
import com.notifications.model.Channel;
import com.notifications.model.DeliveryStatus;
import com.notifications.model.Failure;
import com.notifications.repository.NotificationDeliveryRepository;
import com.notifications.retry.RetryPolicy;
import com.notifications.retry.RetryPolicyRegistry;
import com.notifications.sender.NonRetryableSendException;
import com.notifications.sender.NotificationSender;
import com.notifications.sender.NotificationSenderFactory;
import com.notifications.sender.RetryableSendException;
import com.notifications.sender.SendResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeliveryDispatchServiceTest {

    @Mock private NotificationDeliveryRepository deliveryRepository;
    @Mock private NotificationSenderFactory senderFactory;
    @Mock private RetryPolicyRegistry retryPolicyRegistry;
    @Mock private DlqService dlqService;
    @Mock private NotificationSender sender;
    @Mock private RetryPolicy retryPolicy;

    private DeliveryDispatchService dispatchService;

    @BeforeEach
    void setUp() {
        dispatchService =
                new DeliveryDispatchService(deliveryRepository, senderFactory, retryPolicyRegistry, dlqService, 2000);
    }

    @Test
    void marksDelivered_onSuccessfulSend() {
        NotificationDelivery delivery = pendingDelivery();
        when(deliveryRepository.findById("d1")).thenReturn(Optional.of(delivery));
        when(senderFactory.getSender(Channel.EMAIL)).thenReturn(sender);
        when(sender.send(delivery)).thenReturn(SendResult.success("provider-id"));

        dispatchService.process("d1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        verify(dlqService, never()).record(any(), any());
    }

    @Test
    void movesToFailed_andRecordsDlq_onNonRetryableFailure() {
        NotificationDelivery delivery = pendingDelivery();
        when(deliveryRepository.findById("d1")).thenReturn(Optional.of(delivery));
        when(senderFactory.getSender(Channel.EMAIL)).thenReturn(sender);
        when(sender.send(delivery)).thenThrow(new NonRetryableSendException("invalid recipient"));

        dispatchService.process("d1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastError()).isEqualTo("invalid recipient");
        verify(dlqService).record(delivery, "invalid recipient");
    }

    @Test
    void schedulesRetry_whenRetryableFailureAndPolicyAllows() {
        NotificationDelivery delivery = pendingDelivery();
        when(deliveryRepository.findById("d1")).thenReturn(Optional.of(delivery));
        when(senderFactory.getSender(Channel.EMAIL)).thenReturn(sender);
        when(sender.send(delivery)).thenThrow(new RetryableSendException("temporarily unavailable"));
        when(retryPolicyRegistry.get(Channel.EMAIL)).thenReturn(retryPolicy);
        when(retryPolicy.shouldRetry(1, Failure.retryable("temporarily unavailable"))).thenReturn(true);
        when(retryPolicy.nextBackoff(1)).thenReturn(Duration.ofSeconds(5));

        Instant before = Instant.now();
        dispatchService.process("d1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isAfter(before.plusSeconds(4));
        verify(dlqService, never()).record(any(), any());
    }

    @Test
    void exhaustsToDlq_whenRetryableFailureButPolicyDenies() {
        NotificationDelivery delivery = pendingDelivery();
        delivery.setAttemptCount(2);
        when(deliveryRepository.findById("d1")).thenReturn(Optional.of(delivery));
        when(senderFactory.getSender(Channel.EMAIL)).thenReturn(sender);
        when(sender.send(delivery)).thenThrow(new RetryableSendException("still down"));
        when(retryPolicyRegistry.get(Channel.EMAIL)).thenReturn(retryPolicy);
        when(retryPolicy.shouldRetry(3, Failure.retryable("still down"))).thenReturn(false);

        dispatchService.process("d1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.EXHAUSTED);
        assertThat(delivery.getAttemptCount()).isEqualTo(3);
        verify(dlqService).record(delivery, "still down");
    }

    @Test
    void reschedulesWithoutConsumingAttempt_whenRateLimited() {
        NotificationDelivery delivery = pendingDelivery();
        when(deliveryRepository.findById("d1")).thenReturn(Optional.of(delivery));
        when(senderFactory.getSender(Channel.EMAIL)).thenReturn(sender);
        when(sender.send(delivery)).thenThrow(new RateLimitExceededException("rate limited"));

        dispatchService.process("d1");

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(delivery.getAttemptCount()).isEqualTo(0);
        assertThat(delivery.getNextAttemptAt()).isNotNull();
        verify(dlqService, never()).record(any(), any());
    }

    @Test
    void isNoOp_whenDeliveryAlreadyTerminal() {
        NotificationDelivery delivery = pendingDelivery();
        delivery.setStatus(DeliveryStatus.DELIVERED);
        when(deliveryRepository.findById("d1")).thenReturn(Optional.of(delivery));

        dispatchService.process("d1");

        verify(senderFactory, never()).getSender(any());
    }

    @Test
    void isNoOp_whenDeliveryNotFound() {
        when(deliveryRepository.findById("missing")).thenReturn(Optional.empty());

        dispatchService.process("missing");

        verify(senderFactory, never()).getSender(any());
        verify(deliveryRepository, times(0)).save(any());
    }

    private NotificationDelivery pendingDelivery() {
        Instant now = Instant.now();
        return NotificationDelivery.builder()
                .id("d1")
                .notificationId("n1")
                .userId("u1")
                .notificationType("ORDER_SHIPPED")
                .channel(Channel.EMAIL)
                .status(DeliveryStatus.PENDING)
                .attemptCount(0)
                .payload(new java.util.HashMap<>())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
