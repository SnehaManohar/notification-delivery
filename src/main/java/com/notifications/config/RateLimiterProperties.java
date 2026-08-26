package com.notifications.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Token bucket capacity/refill-rate for each rate-limiting dimension. */
@ConfigurationProperties(prefix = "notification.rate-limit")
@Getter
@Setter
public class RateLimiterProperties {

    private Bucket user = new Bucket(20, 5);
    private Bucket channel = new Bucket(50, 15);

    @Getter
    @Setter
    public static class Bucket {
        private double capacity;
        private double refillPerSecond;

        public Bucket() {}

        public Bucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
        }
    }
}
