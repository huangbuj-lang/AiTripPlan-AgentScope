package utils;

/** 令牌桶限速器：控制 API 请求频率 */
public class RateLimiter {  // 类声明：令牌桶限速器

    private double rate;              // 每秒补充的令牌数（=每秒最大请求数）  // rate：每秒补充令牌数（= 每秒最大请求数）
    private int capacity;             // 桶容量（允许的突发量）  // capacity：桶容量（允许的突发量）
    private double tokens;            // 当前令牌数  // tokens：当前令牌数
    private long lastRefillNanos;     // 上次补充时间戳  // lastRefillNanos：上次补充时间
    /**
     * 作用：私有构造：按给定速率与桶容量初始化令牌桶，初始满桶
     * 参数：rate 每秒补充令牌数；capacity 桶容量（突发上限）
     * 返回值：无
     * 异常：无
     */

    private RateLimiter(double rate, int capacity) {  // 私有构造：初始化令牌桶
        this.rate = rate;  // 保存速率
        this.capacity = capacity;  // 保存容量
        this.tokens = capacity;       // 初始满桶  // 初始满桶
        this.lastRefillNanos = System.nanoTime();  // 记录起始时间
    }  // 构造器结束

    /**
     * 作用：创建限速器，桶容量取速率向上取整（不允许超过每秒配额）
     * 参数：rate 每秒允许的请求数
     * 返回值：初始化好的 RateLimiter 实例
     * 异常：无
     */
    public static RateLimiter create(double rate) {  // create：按速率创建（桶容量=速率向上取整）
        return new RateLimiter(rate, (int) Math.max(1, Math.ceil(rate)));  // 容量至少 1
    }  // 方法结束

    /**
     * 作用：尝试取 1 个令牌，令牌不足时自旋等待直到超时
     * 参数：maxWaitMs 最长等待毫秒数
     * 返回值：true 取到令牌可以发请求；false 等待超时或被中断（此时不消耗令牌）
     * 异常：无（InterruptedException 内部捕获并恢复中断标志后返回 false）
     */
    public synchronized boolean tryAcquire(long maxWaitMs) {  // tryAcquire：取 1 个令牌，不足则等待
        long deadline = System.nanoTime() + maxWaitMs * 1_000_000L;  // deadline：最晚等到什么时候
        while (true) {  // 循环直到取到或超时
            refill();  // 先按时间补令牌
            if (tokens >= 1) {  // 令牌够
                tokens -= 1;  // 扣 1 个
                return true;  // 返回成功
            }  // if 结束
            if (System.nanoTime() >= deadline) {  // 已超时
                return false; // 等待超时  // 返回失败（不消耗令牌）
            }  // if 结束
            //等待下一个补充周期（1/rate 秒）
            long waitNanos = (long) (1_000_000_000.0 / rate);  // waitNanos：一个补充周期的纳秒数
            try {  // try
                long remaining = deadline - System.nanoTime();  // remaining：距 deadline 还剩多少
                Thread.sleep(Math.min(waitNanos / 1_000_000, Math.max(1, remaining / 1_000_000)));  // 睡 min(一个周期, 剩余时间)，至少 1ms
            } catch (InterruptedException e) {  // 捕获中断
                Thread.currentThread().interrupt();  // 恢复中断标志
                return false;  // 返回失败
            }  // catch 结束
        }  // while 结束
    }  // 方法结束

    /**
     * 作用：按距上次补充经过的时间向桶内补令牌，上限为桶容量
     * 参数：无
     * 返回值：无
     * 异常：无
     */
    private void refill() {  // refill：按经过时间补令牌
        long now = System.nanoTime();  // now
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;  // elapsedSec：距上次补充的秒数
        tokens = Math.min(capacity, tokens + elapsedSec * rate);  // 补令牌，上限为容量
        lastRefillNanos = now;  // 更新上次补充时间
    }  // 方法结束

    /**
     * 作用：动态调整限速速率，同时把桶容量同步为速率向上取整
     * 参数：newRate 新的每秒请求数，≤0 时忽略本次调整
     * 返回值：无
     * 异常：无
     */
    public synchronized void setRate(double newRate) {  // setRate：热调速率
        if (newRate <= 0) return;  // 速率非法直接忽略
        this.rate = newRate;  // 更新速率
        this.capacity = (int) Math.max(1, Math.ceil(newRate));  // 桶容量同步为速率向上取整
    }  // 方法结束
}  // 类结束
