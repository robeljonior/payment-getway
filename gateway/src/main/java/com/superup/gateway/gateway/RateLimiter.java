package com.superup.gateway.gateway;

import com.superup.gateway.GatewayProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Rate limits from docs/core/api-conventions.md section 7.
 * Phase 1 runs one api server, so the counters live in memory. With several servers,
 * they move to a shared store.
 */
@Component
public class RateLimiter {

    private final GatewayProperties.RateLimit limits;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(GatewayProperties properties) {
        this.limits = properties.rateLimit();
    }

    /** Every request with a valid key. */
    public TokenBucket perKey(String keyId) {
        return bucket("key:" + keyId, limits.perKeyBurst(), limits.perKeyPerSecond());
    }

    /** POST /payment-sessions has its own, lower limit. */
    public TokenBucket createSession(String keyId) {
        int rate = limits.createSessionPerSecond();
        return bucket("create:" + keyId, rate, rate);
    }

    /** Requests without a valid key, counted by IP address. */
    public TokenBucket perIp(String ip) {
        int rate = limits.perIpPerSecond();
        return bucket("ip:" + ip, rate, rate);
    }

    private TokenBucket bucket(String name, int capacity, int perSecond) {
        return buckets.computeIfAbsent(name, n -> new TokenBucket(capacity, perSecond, System.nanoTime()));
    }
}
