package com.notifications.ratelimit;

/**
 * Independent of notification sending: the algorithm behind rate limiting can change (token
 * bucket, fixed window, sliding window) without touching any sender.
 */
public interface RateLimiter {

    boolean allow(RateLimitKey key);
}
