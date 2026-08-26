package com.notifications.sender;

import com.notifications.entity.NotificationDelivery;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** SMS-channel strategy. See {@link SimulatedProviderBehavior} for how to control its outcome. */
@Component
public class SmsSender implements NotificationSender {

    @Override
    public SendResult send(NotificationDelivery delivery) {
        SimulatedProviderBehavior.evaluate(delivery, "SMS");
        return SendResult.success("sms-" + UUID.randomUUID());
    }
}
