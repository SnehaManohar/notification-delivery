package com.notifications.sender;

import com.notifications.entity.NotificationDelivery;

/**
 * A single send attempt for one channel. Every concrete channel implementation and every
 * decorator (logging, rate limiting) implements exactly this contract - the caller never knows
 * whether it's talking to a raw sender or a stack of decorators wrapping one.
 */
public interface NotificationSender {

    SendResult send(NotificationDelivery delivery);
}
