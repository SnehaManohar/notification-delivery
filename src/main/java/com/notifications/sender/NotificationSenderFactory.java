package com.notifications.sender;

import com.notifications.model.Channel;
import java.util.Map;

/**
 * Runtime Channel -> NotificationSender lookup. This is the only reason a factory exists here:
 * the implementation must be selected dynamically based on a delivery's channel. It does not
 * hide any other complexity, so a single map-backed class is sufficient - no builder, no
 * hierarchy, no abstract factory.
 *
 * <p>The senders held here are already fully decorated (logging + rate limiting wrapped around
 * the raw channel sender), so callers never need to know whether they're invoking a raw sender
 * or a stack of decorators - the {@link NotificationSender} contract is identical either way.
 */
public class NotificationSenderFactory {

    private final Map<Channel, NotificationSender> senders;

    public NotificationSenderFactory(Map<Channel, NotificationSender> senders) {
        this.senders = senders;
    }

    public NotificationSender getSender(Channel channel) {
        NotificationSender sender = senders.get(channel);
        if (sender == null) {
            throw new IllegalArgumentException("No sender registered for channel: " + channel);
        }
        return sender;
    }
}
