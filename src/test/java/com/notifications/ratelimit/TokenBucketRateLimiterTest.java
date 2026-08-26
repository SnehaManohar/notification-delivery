package com.notifications.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.notifications.config.RateLimiterProperties;
import com.notifications.model.Channel;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    @Test
    void allowsUpToCapacityThenRejects() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setUser(new RateLimiterProperties.Bucket(3, 0.0001));
        properties.setChannel(new RateLimiterProperties.Bucket(100, 100));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties);

        RateLimitKey key = RateLimitKey.forUser("u1");

        assertThat(limiter.allow(key)).isTrue();
        assertThat(limiter.allow(key)).isTrue();
        assertThat(limiter.allow(key)).isTrue();
        // capacity exhausted, refill rate is effectively zero within this test's timeframe
        assertThat(limiter.allow(key)).isFalse();
    }

    @Test
    void buckets_areIndependentPerKey() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setUser(new RateLimiterProperties.Bucket(1, 0.0001));
        properties.setChannel(new RateLimiterProperties.Bucket(1, 0.0001));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties);

        assertThat(limiter.allow(RateLimitKey.forUser("u1"))).isTrue();
        // u1's user-bucket is now empty, but u2 has its own independent bucket
        assertThat(limiter.allow(RateLimitKey.forUser("u2"))).isTrue();
        assertThat(limiter.allow(RateLimitKey.forUser("u1"))).isFalse();

        // channel-scoped bucket is independent from user-scoped bucket
        assertThat(limiter.allow(RateLimitKey.forChannel(Channel.EMAIL))).isTrue();
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        RateLimiterProperties properties = new RateLimiterProperties();
        // capacity 1, refill 1000 tokens/sec -> refills within a few milliseconds
        properties.setUser(new RateLimiterProperties.Bucket(1, 1000));
        properties.setChannel(new RateLimiterProperties.Bucket(100, 100));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties);

        RateLimitKey key = RateLimitKey.forUser("u1");
        assertThat(limiter.allow(key)).isTrue();
        assertThat(limiter.allow(key)).isFalse();

        Thread.sleep(20);

        assertThat(limiter.allow(key)).isTrue();
    }
}
