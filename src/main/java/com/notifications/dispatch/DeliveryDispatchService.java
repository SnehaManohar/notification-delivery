package com.notifications.dispatch;

import com.notifications.decorator.RateLimitExceededException;
import com.notifications.dlq.DlqService;
import com.notifications.entity.NotificationDelivery;
import com.notifications.model.DeliveryStatus;
import com.notifications.repository.NotificationDeliveryRepository;
import com.notifications.retry.RetryPolicy;
import com.notifications.retry.RetryPolicyRegistry;
import com.notifications.sender.NonRetryableSendException;
import com.notifications.sender.NotificationSender;
import com.notifications.sender.NotificationSenderFactory;
import com.notifications.sender.RetryableSendException;
import com.notifications.sender.SendException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consumer/dispatcher: retrieves the correct composed sender for a delivery's channel and
 * drives exactly one dispatch cycle. It does not implement retry backoff or rate limiting math
 * itself (those live in RetryPolicy and RateLimiter/RateLimitedSender), but it does own the
 * decision of what a given outcome means for delivery state - retryable vs. exhausted vs.
 * rate-limited-so-reschedule vs. permanently failed.
 *
 * <p>Retry is intentionally NOT implemented as a blocking sender decorator here: exponential
 * backoff can mean minutes between attempts, and blocking a worker thread for that long would
 * defeat the point of an asynchronous pipeline. Instead a retryable failure updates the
 * delivery's state (RETRYING + nextAttemptAt) and returns; {@link DeliveryReconciliationScheduler}
 * republishes it once due. RetryPolicy itself remains an independent, swappable strategy either
 * way - only *where* it's consulted differs from the interview script's synchronous decorator.
 */
@Service
public class DeliveryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatchService.class);

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationSenderFactory senderFactory;
    private final RetryPolicyRegistry retryPolicyRegistry;
    private final DlqService dlqService;
    private final Duration rateLimitRetryDelay;

    public DeliveryDispatchService(
            NotificationDeliveryRepository deliveryRepository,
            NotificationSenderFactory senderFactory,
            RetryPolicyRegistry retryPolicyRegistry,
            DlqService dlqService,
            @Value("${notification.dispatch.rate-limit-retry-delay-millis:2000}") long rateLimitRetryDelayMillis) {
        this.deliveryRepository = deliveryRepository;
        this.senderFactory = senderFactory;
        this.retryPolicyRegistry = retryPolicyRegistry;
        this.dlqService = dlqService;
        this.rateLimitRetryDelay = Duration.ofMillis(rateLimitRetryDelayMillis);
    }

    @Transactional
    public void process(String deliveryId) {
        Optional<NotificationDelivery> maybeDelivery = deliveryRepository.findById(deliveryId);
        if (maybeDelivery.isEmpty()) {
            log.warn("No delivery found for id={}, skipping", deliveryId);
            return;
        }

        NotificationDelivery delivery = maybeDelivery.get();
        if (isTerminal(delivery.getStatus())) {
            // Duplicate queue message for an already-finished delivery - at-least-once means
            // this can happen; it's a no-op rather than an error.
            return;
        }

        delivery.setStatus(DeliveryStatus.DISPATCHING);
        deliveryRepository.save(delivery);

        NotificationSender sender = senderFactory.getSender(delivery.getChannel());

        try {
            sender.send(delivery);
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            markDelivered(delivery);

        } catch (RateLimitExceededException e) {
            rescheduleForRateLimit(delivery, e.getMessage());

        } catch (NonRetryableSendException e) {
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            markFailedPermanently(delivery, e);

        } catch (RetryableSendException e) {
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            handleRetryableFailure(delivery, e);

        } catch (SendException e) {
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            handleRetryableFailure(delivery, e);
        }
    }

    private void markDelivered(NotificationDelivery delivery) {
        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setLastError(null);
        delivery.setUpdatedAt(Instant.now());
        deliveryRepository.save(delivery);
    }

    private void rescheduleForRateLimit(NotificationDelivery delivery, String reason) {
        delivery.setStatus(DeliveryStatus.RETRYING);
        delivery.setNextAttemptAt(Instant.now().plus(rateLimitRetryDelay));
        delivery.setLastError(reason);
        delivery.setUpdatedAt(Instant.now());
        deliveryRepository.save(delivery);
    }

    private void markFailedPermanently(NotificationDelivery delivery, SendException e) {
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setLastError(e.getMessage());
        delivery.setUpdatedAt(Instant.now());
        deliveryRepository.save(delivery);
        dlqService.record(delivery, e.getMessage());
    }

    private void handleRetryableFailure(NotificationDelivery delivery, SendException e) {
        RetryPolicy retryPolicy = retryPolicyRegistry.get(delivery.getChannel());
        boolean shouldRetry = retryPolicy.shouldRetry(delivery.getAttemptCount(), e.failure());

        if (shouldRetry) {
            delivery.setStatus(DeliveryStatus.RETRYING);
            delivery.setNextAttemptAt(Instant.now().plus(retryPolicy.nextBackoff(delivery.getAttemptCount())));
            delivery.setLastError(e.getMessage());
            delivery.setUpdatedAt(Instant.now());
            deliveryRepository.save(delivery);
        } else {
            delivery.setStatus(DeliveryStatus.EXHAUSTED);
            delivery.setLastError(e.getMessage());
            delivery.setUpdatedAt(Instant.now());
            deliveryRepository.save(delivery);
            dlqService.record(delivery, e.getMessage());
        }
    }

    private boolean isTerminal(DeliveryStatus status) {
        return status == DeliveryStatus.DELIVERED
                || status == DeliveryStatus.FAILED
                || status == DeliveryStatus.EXHAUSTED;
    }
}
