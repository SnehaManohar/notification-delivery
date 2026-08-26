package com.notifications.ratelimit;

import com.notifications.config.RateLimiterProperties;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One bucket per (scope, value) key, created lazily on first use. "user" and "channel" scopes
 * are configured independently (see {@link RateLimiterProperties}) so a busy channel doesn't
 * starve a quiet user's budget and vice versa.
 */
@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private final ConcurrentHashMap<RateLimitKey, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final RateLimiterProperties properties;

    public TokenBucketRateLimiter(RateLimiterProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean allow(RateLimitKey key) {
        TokenBucket bucket = buckets.computeIfAbsent(key, this::newBucket);
        return bucket.tryConsume();
    }

    private TokenBucket newBucket(RateLimitKey key) {
        RateLimiterProperties.Bucket config =
                "user".equals(key.scope()) ? properties.getUser() : properties.getChannel();
        return new TokenBucket(config.getCapacity(), config.getRefillPerSecond());
    }
}
