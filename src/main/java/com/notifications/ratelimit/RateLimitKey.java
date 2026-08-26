package com.notifications.ratelimit;

import com.notifications.model.Channel;

/**
 * A dispatch is allowed only when both the user-level bucket and the channel-level bucket
 * have a token available, so RateLimiter checks are keyed independently on each dimension.
 */
public record RateLimitKey(String scope, String value) {

    public static RateLimitKey forUser(String userId) {
        return new RateLimitKey("user", userId);
    }

    public static RateLimitKey forChannel(Channel channel) {
        return new RateLimitKey("channel", channel.name());
    }
}
