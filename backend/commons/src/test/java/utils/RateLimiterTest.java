package utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 令牌桶限速器单元测试 */
class RateLimiterTest {

    @Test
    void immediatelyAcquiresWhenTokensAvailable() {
        RateLimiter limiter = RateLimiter.create(10);
        assertTrue(limiter.tryAcquire(0));
    }

    @Test
    void blocksAndReturnsFalseWhenExhausted() {
        RateLimiter limiter = RateLimiter.create(1); // 容量=1，初始1个令牌
        assertTrue(limiter.tryAcquire(0));
        assertFalse(limiter.tryAcquire(0)); // 令牌耗尽且不等待 → false
    }
}
