package com.superup.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings from application.properties, under "gateway.". */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        String checkoutBaseUrl,
        int feeBasisPoints,
        RateLimit rateLimit) {

    /** Requests per second. Test and live keys are counted separately because they are separate keys. */
    public record RateLimit(
            int perKeyPerSecond,
            int perKeyBurst,
            int createSessionPerSecond,
            int perIpPerSecond) {
    }
}
