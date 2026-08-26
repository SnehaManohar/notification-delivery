package com.notifications.sender;

import com.notifications.entity.NotificationDelivery;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Push-channel strategy. See {@link SimulatedProviderBehavior} for how to control its outcome. */
@Component
public class PushSender implements NotificationSender {

    @Override
    public SendResult send(NotificationDelivery delivery) {
        SimulatedProviderBehavior.evaluate(delivery, "Push");
        return SendResult.success("push-" + UUID.randomUUID());
    }
}
