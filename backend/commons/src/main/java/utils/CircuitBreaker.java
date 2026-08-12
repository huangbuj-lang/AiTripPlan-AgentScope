package utils;

/**
 * 熔断器：API 连续失败时熔断，自动降级到备用模型（Qwen），防止反复失败烧钱+卡线程
 *
 * 状态机：
 *   CLOSED（正常）  → 窗口内累计失败 ≥ failureThreshold 次 → OPEN（熔断）
 *   OPEN（熔断中）   → 使用降级模型；冷却时间过后 → HALF_OPEN（半开试探）
 *   HALF_OPEN（试探）→ 试探请求成功 → CLOSED（恢复主模型）；失败 → 重新 OPEN
 *
 * ★失败统计用"滑动窗口"（时间窗口内累计次数），不是"单任务内连续次数"——
 *  一个任务可能只调用几次模型就失败终止（如401认证错），要求单任务内连续5次失败永远达不到。
 *  窗口内累计跨任务统计，更符合"服务端持续故障"的真实场景。
 *
 * 线程安全：全部方法 synchronized，全局单例（静态），多 Agent 共享
 */
public class CircuitBreaker {

    /** 熔断器状态 */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final int FAILURE_THRESHOLD = 5;     // 窗口内累计失败多少次触发熔断
    private static long WINDOW_MS = 10_000;             // 失败统计窗口（10秒内累计5次即熔断；可配置）
    private static long COOL_DOWN_MS = 60_000;          // 熔断冷却时间（1分钟后再试探；可配置）

    private State state = State.CLOSED;
    private long[] failureTimes = new long[FAILURE_THRESHOLD]; // 滑动窗口：记录最近N次失败时间戳
    private int failureCount = 0;                       // 窗口内失败次数
    private long openedAtNanos = 0;                     // 进入 OPEN 的时间

    private static final CircuitBreaker INSTANCE = new CircuitBreaker();

    private CircuitBreaker() {}

    public static CircuitBreaker getInstance() {
        return INSTANCE;
    }

    /** 是否处于熔断状态（true → 调用方应使用降级模型；false → 使用主模型） */
    public synchronized boolean isOpen() {
        if (state == State.CLOSED) {
            return false;
        }
        if (state == State.OPEN) {
            // 冷却时间到了 → 转半开：放行一次主模型请求试探恢复（isOpen 返回 false → 调用方走主模型）
            if (System.nanoTime() - openedAtNanos >= COOL_DOWN_MS * 1_000_000L) {
                state = State.HALF_OPEN;
                return false; // HALF_OPEN = 允许主模型试探
            }
            return true; // 冷却期内：继续熔断（用降级模型）
        }
        // HALF_OPEN：允许主模型试探（isOpen=false）
        return false;
    }

    /** 记录一次成功调用（成功→恢复正常，重置失败计数） */
    public synchronized void onSuccess() {
        failureCount = 0;
        if (state == State.HALF_OPEN) {
            state = State.CLOSED; // 试探成功：恢复主模型
        }
    }

    /** 记录一次失败调用（窗口内累计达到阈值 → 熔断；半开试探失败 → 立即重新熔断） */
    public synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            // 半开试探失败：主模型还没恢复，立即重新熔断（重置冷却计时）
            state = State.OPEN;
            openedAtNanos = System.nanoTime();
            failureCount = 1;
            return;
        }
        long now = System.currentTimeMillis();
        // 滑动窗口：把窗口外的旧失败记录挤掉
        int kept = 0;
        for (int i = 0; i < failureCount; i++) {
            if (now - failureTimes[i] < WINDOW_MS) {
                failureTimes[kept++] = failureTimes[i];
            }
        }
        failureCount = kept;
        failureTimes[failureCount++] = now; // 记录本次失败
        if (failureCount >= FAILURE_THRESHOLD) {
            state = State.OPEN;
            openedAtNanos = System.nanoTime();
        }
    }

    /** 强制立即熔断（认证类错误用：key坏了重试无意义，直接切备用模型，不等待失败次数） */
    public synchronized void forceOpen() {
        state = State.OPEN;
        openedAtNanos = System.nanoTime();
        failureCount = FAILURE_THRESHOLD; // 直接填满计数（恢复后重新计数）
    }

    /** 当前熔断状态（测试用/日志用） */
    public synchronized State getState() {
        return state;
    }

    /** 窗口内失败次数（测试用/日志用） */
    public synchronized int getConsecutiveFailures() {
        return failureCount;
    }

    /** 设置熔断冷却时间（毫秒；默认60秒。测试/云端调优用） */
    public static void setCoolDownMs(long ms) {
        COOL_DOWN_MS = ms;
    }

    /** 设置失败统计窗口（毫秒；默认10秒） */
    public static void setWindowMs(long ms) {
        WINDOW_MS = ms;
    }
}
