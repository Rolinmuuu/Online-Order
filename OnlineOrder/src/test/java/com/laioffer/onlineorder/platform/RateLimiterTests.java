package com.laioffer.onlineorder.platform;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTests {

    static final long SECOND = 1_000_000_000L;
    final AtomicLong now = new AtomicLong();

    @Test
    void allowsABurstUpToCapacityThenRejectsWithTheWaitUntilTheNextToken() {
        RateLimiter limiter = new RateLimiter(3, 60 * SECOND, now::get); // 1 token per 20 s
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("a").allowed());
        }
        RateLimiter.Decision rejected = limiter.tryAcquire("a");
        assertFalse(rejected.allowed());
        assertEquals(20, rejected.retryAfterSeconds());

        now.addAndGet(20 * SECOND);
        assertTrue(limiter.tryAcquire("a").allowed());
        assertFalse(limiter.tryAcquire("a").allowed());
    }

    @Test
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(1, SECOND, now::get);
        assertTrue(limiter.tryAcquire("a").allowed());
        assertFalse(limiter.tryAcquire("a").allowed());
        assertTrue(limiter.tryAcquire("b").allowed());
    }

    @Test
    void refillNeverExceedsCapacity() {
        RateLimiter limiter = new RateLimiter(2, SECOND, now::get);
        now.addAndGet(3600 * SECOND);
        assertTrue(limiter.tryAcquire("a").allowed());
        assertTrue(limiter.tryAcquire("a").allowed());
        assertFalse(limiter.tryAcquire("a").allowed());
    }

    @Test
    void idleKeysAreEvictedWhenTheMapIsFull() {
        RateLimiter limiter = new RateLimiter(1, SECOND, now::get);
        for (int i = 0; i < RateLimiter.MAX_KEYS; i++) {
            limiter.tryAcquire("k" + i);
        }
        now.addAndGet(2 * SECOND); // every bucket has refilled: nothing worth remembering
        limiter.tryAcquire("new");
        assertEquals(1, limiter.size());
    }
}
