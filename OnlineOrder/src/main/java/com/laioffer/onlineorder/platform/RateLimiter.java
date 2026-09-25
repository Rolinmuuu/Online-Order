package com.laioffer.onlineorder.platform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token buckets, one per key: {@code capacity} requests at once, refilled continuously at
 * {@code capacity} per {@code periodNanos}. A bucket that has refilled completely holds no
 * information, so idle keys are dropped when the map grows, which bounds memory under a flood of
 * distinct keys.
 *
 * <p>Limits are per instance. With N instances a client gets up to N times the limit, which is
 * acceptable for abuse protection; a shared limit would move the buckets to Redis.
 */
public class RateLimiter {

    /** Result of an attempt: allowed, or rejected with how long until a token is available. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private static final class Bucket {
        double tokens;
        long updatedAt;

        Bucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }
    }

    static final int MAX_KEYS = 50_000;

    private final int capacity;
    private final double tokensPerNano;
    private final LongSupplier nanoTime;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int capacity, long periodNanos, LongSupplier nanoTime) {
        this.capacity = capacity;
        this.tokensPerNano = (double) capacity / periodNanos;
        this.nanoTime = nanoTime;
    }

    public Decision tryAcquire(String key) {
        long now = nanoTime.getAsLong();
        if (buckets.size() >= MAX_KEYS) {
            evictFull(now);
        }
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (b) {
            b.tokens = Math.min(capacity, b.tokens + (now - b.updatedAt) * tokensPerNano);
            b.updatedAt = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return new Decision(true, 0);
            }
            long waitNanos = (long) Math.ceil((1 - b.tokens) / tokensPerNano);
            return new Decision(false, Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L));
        }
    }

    private void evictFull(long now) {
        buckets.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            synchronized (b) {
                return b.tokens + (now - b.updatedAt) * tokensPerNano >= capacity;
            }
        });
    }

    int size() {
        return buckets.size();
    }
}
