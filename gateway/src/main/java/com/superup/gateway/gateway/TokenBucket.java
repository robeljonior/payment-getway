package com.superup.gateway.gateway;

/**
 * A simple token bucket: it holds up to {@code capacity} tokens and refills
 * {@code perSecond} tokens every second. Each request takes one token.
 */
public final class TokenBucket {

    private final double capacity;
    private final double perSecond;
    private double tokens;
    private long lastNanos;

    public TokenBucket(int capacity, int perSecond, long nowNanos) {
        this.capacity = capacity;
        this.perSecond = perSecond;
        this.tokens = capacity;
        this.lastNanos = nowNanos;
    }

    /** Takes one token if there is one. */
    public synchronized boolean tryTake(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    public synchronized int remaining(long nowNanos) {
        refill(nowNanos);
        return (int) Math.floor(tokens);
    }

    public int capacity() {
        return (int) capacity;
    }

    private void refill(long nowNanos) {
        double seconds = (nowNanos - lastNanos) / 1_000_000_000.0;
        if (seconds > 0) {
            tokens = Math.min(capacity, tokens + seconds * perSecond);
            lastNanos = nowNanos;
        }
    }
}
