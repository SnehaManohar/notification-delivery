package com.notifications.decorator;

import com.notifications.entity.NotificationDelivery;
import com.notifications.ratelimit.RateLimitKey;
import com.notifications.ratelimit.RateLimiter;
import com.notifications.sender.NotificationSender;
import com.notifications.sender.SendResult;

/**
 * Wraps a sender with a both-must-allow check: the delivery is only forwarded to the delegate
 * if both the user-level and the channel-level bucket have a token available. Placed directly
 * around the raw channel sender (innermost, next to {@link com.notifications.sender.EmailSender}
 * and its siblings) so the limit reflects actual provider calls, including calls made on retry -
 * this is a provider-facing limit, not a "did we ever try" limit.
 */
public class RateLimitedSender implements NotificationSender {

    private final NotificationSender delegate;
    private final RateLimiter rateLimiter;

    public RateLimitedSender(NotificationSender delegate, RateLimiter rateLimiter) {
        this.delegate = delegate;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public SendResult send(NotificationDelivery delivery) {
        // Short-circuit so a throttled user never burns a token out of the shared channel
        // bucket - only consume the channel token once we know the user side has budget.
        boolean allowed = rateLimiter.allow(RateLimitKey.forUser(delivery.getUserId()))
                && rateLimiter.allow(RateLimitKey.forChannel(delivery.getChannel()));

        if (!allowed) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded for user=" + delivery.getUserId()
                            + " channel=" + delivery.getChannel());
        }

        return delegate.send(delivery);
    }
}
