package utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 熔断器单元测试
 */
class CircuitBreakerTest {

    private final CircuitBreaker breaker = CircuitBreaker.getInstance();

    @BeforeEach
    void resetBreaker() {
        breaker.reset();
        CircuitBreaker.setCoolDownMs(60_000); // 恢复默认冷却，避免跨测试污染
        CircuitBreaker.setWindowMs(10_000);
    }

    @Test
    void initialStateIsClosed() {
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertFalse(breaker.isOpen());
    }

    @Test
    void opensAfterConsecutiveFailures() {
        for (int i = 0; i < 20 && !breaker.isOpen(); i++) {
            breaker.onFailure();
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertTrue(breaker.isOpen());
    }

    @Test
    void halfOpenAfterCooldown() {
        breaker.forceOpen();
        CircuitBreaker.setCoolDownMs(0); // 冷却立即结束
        assertFalse(breaker.isOpen()); // 冷却结束：转为半开放行主模型
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());
    }

    @Test
    void successInHalfOpenClosesBreaker() {
        breaker.forceOpen();
        CircuitBreaker.setCoolDownMs(0);
        breaker.isOpen(); // 触发 OPEN → HALF_OPEN
        breaker.onSuccess(); // 半开试探成功 → CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertFalse(breaker.isOpen());
    }

    @Test
    void forceOpenImmediately() {
        breaker.forceOpen();
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertTrue(breaker.isOpen());
    }
}
