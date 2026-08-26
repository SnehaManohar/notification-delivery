package com.notifications.retry;

import com.notifications.config.RetryProperties;
import com.notifications.model.Channel;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Channel -> RetryPolicy lookup, built once from configuration at startup. */
@Component
public class RetryPolicyRegistry {

    private final Map<Channel, RetryPolicy> policies = new EnumMap<>(Channel.class);

    public RetryPolicyRegistry(RetryProperties properties) {
        for (Channel channel : Channel.values()) {
            RetryProperties.ChannelPolicy config =
                    properties.getChannels().getOrDefault(channel.name(), properties.getDefaultPolicy());
            policies.put(
                    channel,
                    new ExponentialBackoffRetryPolicy(
                            config.getMaxRetries(),
                            Duration.ofMillis(config.getInitialBackoffMillis()),
                            config.getMultiplier(),
                            Duration.ofMillis(config.getMaxBackoffMillis())));
        }
    }

    public RetryPolicy get(Channel channel) {
        return policies.get(channel);
    }
}
