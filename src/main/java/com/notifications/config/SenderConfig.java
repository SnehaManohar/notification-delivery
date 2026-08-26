package com.notifications.config;

import com.notifications.decorator.LoggingSender;
import com.notifications.decorator.RateLimitedSender;
import com.notifications.model.Channel;
import com.notifications.ratelimit.RateLimiter;
import com.notifications.sender.EmailSender;
import com.notifications.sender.NotificationSender;
import com.notifications.sender.NotificationSenderFactory;
import com.notifications.sender.PushSender;
import com.notifications.sender.SmsSender;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composes the decorator chain around each raw channel sender: Logging(RateLimited(raw)).
 * Rate limiting sits directly next to the raw sender so it governs actual provider calls
 * (including retried ones); logging wraps the whole thing so every outcome is recorded once.
 * Retry itself is not a decorator here - see DeliveryDispatchService for why.
 */
@Configuration
public class SenderConfig {

    @Bean
    public NotificationSenderFactory notificationSenderFactory(
            EmailSender emailSender, SmsSender smsSender, PushSender pushSender, RateLimiter rateLimiter) {

        Map<Channel, NotificationSender> senders = new EnumMap<>(Channel.class);
        senders.put(Channel.EMAIL, decorate(emailSender, rateLimiter));
        senders.put(Channel.SMS, decorate(smsSender, rateLimiter));
        senders.put(Channel.PUSH, decorate(pushSender, rateLimiter));

        return new NotificationSenderFactory(senders);
    }

    private NotificationSender decorate(NotificationSender raw, RateLimiter rateLimiter) {
        return new LoggingSender(new RateLimitedSender(raw, rateLimiter));
    }
}
