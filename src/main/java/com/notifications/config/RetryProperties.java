package com.notifications.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-channel retry budget and backoff curve - see application.yml for the defaults. */
@ConfigurationProperties(prefix = "notification.retry")
@Getter
@Setter
public class RetryProperties {

    private Map<String, ChannelPolicy> channels = new LinkedHashMap<>();
    private ChannelPolicy defaultPolicy = new ChannelPolicy(3, 500, 2.0, 30_000);

    @Getter
    @Setter
    public static class ChannelPolicy {
        private int maxRetries;
        private long initialBackoffMillis;
        private double multiplier;
        private long maxBackoffMillis;

        public ChannelPolicy() {}

        public ChannelPolicy(int maxRetries, long initialBackoffMillis, double multiplier, long maxBackoffMillis) {
            this.maxRetries = maxRetries;
            this.initialBackoffMillis = initialBackoffMillis;
            this.multiplier = multiplier;
            this.maxBackoffMillis = maxBackoffMillis;
        }
    }
}
