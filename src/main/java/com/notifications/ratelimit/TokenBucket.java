package com.notifications.ratelimit;

/**
 * Classic token bucket: tokens refill continuously up to {@code capacity} at {@code refillRate}
 * tokens/sec. Chosen over a fixed window because a fixed window can let two full bursts land
 * back-to-back at a window boundary (e.g. 10 requests at 12:00:59 and 10 more at 12:01:00 -
 * effectively 20 in under a second); a token bucket smooths that out while still allowing
 * controlled bursts up to its capacity.
 */
class TokenBucket {

    private final double capacity;
    private final double refillRatePerSecond;
    private double availableTokens;
    private long lastRefillNanos;

    TokenBucket(double capacity, double refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.availableTokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    synchronized boolean tryConsume() {
        refill();
        if (availableTokens >= 1.0) {
            availableTokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds <= 0) {
            return;
        }
        availableTokens = Math.min(capacity, availableTokens + elapsedSeconds * refillRatePerSecond);
        lastRefillNanos = now;
    }
}
