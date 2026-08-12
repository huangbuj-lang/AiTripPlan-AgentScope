package utils;

/**
 * 令牌桶限速器：控制 API 请求频率，防止撞 DeepSeek/DashScope 的 RPM（每分钟请求数）限流
 *
 * 原理：桶里每秒补充 rate 个令牌，每次请求取 1 个令牌；桶满（capacity）不再累积，
 *       令牌不足时阻塞等待——把突发请求"削峰填谷"，频率平稳在配额内。
 *
 * ★云端部署注意：本实现是进程内的（单实例有效）。云端多实例时需换成 Redis 分布式令牌桶
 *  （Redisson RRateLimiter），否则各实例各自限速=总请求翻倍。接口保持一致即可替换。
 *
 * 用法：RateLimiter limiter = RateLimiter.create(3); // 每秒最多3个请求
 *       limiter.acquire();  // 取令牌（不足则阻塞等待）
 */
public class RateLimiter {

    private final double rate;        // 每秒补充的令牌数（=每秒最大请求数）
    private final int capacity;       // 桶容量（允许的突发量）
    private double tokens;            // 当前令牌数
    private long lastRefillNanos;     // 上次补充时间戳

    private RateLimiter(double rate, int capacity) {
        this.rate = rate;
        this.capacity = capacity;
        this.tokens = capacity;       // 初始满桶（允许刚开始的少量突发）
        this.lastRefillNanos = System.nanoTime();
    }

    /** 创建限速器（每秒 rate 个令牌，桶容量=rate 即不允许突发） */
    public static RateLimiter create(double rate) {
        return new RateLimiter(rate, (int) Math.max(1, Math.ceil(rate)));
    }

    /** 取 1 个令牌；令牌不足时阻塞等待（最多等 maxWaitMs，超时返回 false） */
    public synchronized boolean tryAcquire(long maxWaitMs) {
        long deadline = System.nanoTime() + maxWaitMs * 1_000_000L;
        while (true) {
            refill();
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false; // 等待超时
            }
            // 等待下一个补充周期（1/rate 秒）
            long waitNanos = (long) (1_000_000_000.0 / rate);
            try {
                long remaining = deadline - System.nanoTime();
                Thread.sleep(Math.min(waitNanos / 1_000_000, Math.max(1, remaining / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** 补充令牌（按经过的时间） */
    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsedSec * rate);
        lastRefillNanos = now;
    }
}
