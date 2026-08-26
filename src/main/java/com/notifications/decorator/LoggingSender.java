package com.notifications.decorator;

import com.notifications.entity.NotificationDelivery;
import com.notifications.sender.NotificationSender;
import com.notifications.sender.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outermost decorator: logs every attempt, success, and failure without altering the outcome.
 * Wraps the whole chain (rate limiting included) so a single log line accounts for the full
 * decision made for this delivery.
 */
public class LoggingSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSender.class);

    private final NotificationSender delegate;

    public LoggingSender(NotificationSender delegate) {
        this.delegate = delegate;
    }

    @Override
    public SendResult send(NotificationDelivery delivery) {
        log.info(
                "Dispatching delivery deliveryId={} notificationId={} channel={} attempt={}",
                delivery.getId(),
                delivery.getNotificationId(),
                delivery.getChannel(),
                delivery.getAttemptCount());
        try {
            SendResult result = delegate.send(delivery);
            log.info(
                    "Delivered deliveryId={} channel={} providerMessageId={}",
                    delivery.getId(),
                    delivery.getChannel(),
                    result.providerMessageId());
            return result;
        } catch (RuntimeException e) {
            log.warn(
                    "Delivery attempt failed deliveryId={} channel={} attempt={} reason={}",
                    delivery.getId(),
                    delivery.getChannel(),
                    delivery.getAttemptCount(),
                    e.getMessage());
            throw e;
        }
    }
}
