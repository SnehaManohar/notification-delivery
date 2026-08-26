package com.notifications.sender;

import com.notifications.entity.NotificationDelivery;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Email-channel strategy. See {@link SimulatedProviderBehavior} for how to control its outcome. */
@Component
public class EmailSender implements NotificationSender {

    @Override
    public SendResult send(NotificationDelivery delivery) {
        SimulatedProviderBehavior.evaluate(delivery, "Email");
        return SendResult.success("email-" + UUID.randomUUID());
    }
}
