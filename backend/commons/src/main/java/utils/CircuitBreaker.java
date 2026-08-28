package utils;

/**
 * 熔断器：连续失败时熔断并降级备用模型
 */
public class CircuitBreaker {

    /** 熔断器状态 */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static volatile int failureThreshold = Math.max(1, Config.getInt("BREAKER_FAILURE_THRESHOLD", 5)); // 窗口内失败累计阈值
    private static volatile long windowMs = Config.getLong("BREAKER_WINDOW_MS", 10_000);    // 失败统计窗口（毫秒）
    private static volatile long coolDownMs = Config.getLong("BREAKER_COOL_DOWN_MS", 60_000); // 熔断冷却时间（毫秒）

    private State state = State.CLOSED;
    private final java.util.List<Long> failureTimes = new java.util.ArrayList<>(); // 滑动窗口：记录失败时间戳
    private long openedAtNanos = 0;                     // 进入 OPEN 的时间

    private static final CircuitBreaker INSTANCE = new CircuitBreaker();

    private CircuitBreaker() {}

    public static CircuitBreaker getInstance() {
        return INSTANCE;
    }

    /** 是否处于熔断状态 */
    public synchronized boolean isOpen() {
        if (state == State.CLOSED) {
            return false;
        }
        if (state == State.OPEN) {
            // 冷却结束：转半开，放行主模型试探
            if (System.nanoTime() - openedAtNanos >= coolDownMs * 1_000_000L) {
                state = State.HALF_OPEN;
                return false; // 半开：允许主模型试探
            }
            return true; // 冷却期内：继续熔断
        }
        // 半开状态：允许主模型试探
        return false;
    }

    /** 记录成功调用并重置失败计数 */
    public synchronized void onSuccess() {
        failureTimes.clear();
        if (state == State.HALF_OPEN) {
            state = State.CLOSED; // 试探成功：恢复 CLOSED
        }
    }

    /** 记录失败调用，达到阈值则熔断 */
    public synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            // 半开试探失败：立即重新熔断
            state = State.OPEN;
            openedAtNanos = System.nanoTime();
            failureTimes.clear();
            failureTimes.add(System.currentTimeMillis());
            return;
        }
        long now = System.currentTimeMillis();
        failureTimes.removeIf(t -> now - t >= windowMs);
        failureTimes.add(now);
        if (failureTimes.size() >= failureThreshold) {
            state = State.OPEN;
            openedAtNanos = System.nanoTime();
        }
    }

    /** 强制立即熔断（用于认证类错误） */
    public synchronized void forceOpen() {
        state = State.OPEN;
        openedAtNanos = System.nanoTime();
        long now = System.currentTimeMillis();
        failureTimes.clear();
        for (int i = 0; i < failureThreshold; i++) failureTimes.add(now);
    }

    /** 获取当前熔断状态 */
    public synchronized State getState() {
        return state;
    }

    /** 获取窗口内失败次数 */
    public synchronized int getConsecutiveFailures() {
        return failureTimes.size();
    }

    /** 重置熔断器到初始 CLOSED 状态（管理端/测试用） */
    public synchronized void reset() {
        state = State.CLOSED;
        failureTimes.clear();
        openedAtNanos = 0;
    }

    /** 动态设置失败阈值（Nacos 推送后调用） */
    public static void setFailureThreshold(int n) {
        failureThreshold = Math.max(1, n);
    }

    public static int getFailureThreshold() {
        return failureThreshold;
    }

    public static long getWindowMs() {
        return windowMs;
    }

    public static long getCoolDownMs() {
        return coolDownMs;
    }

    /** 设置熔断冷却时间（毫秒） */
    public static void setCoolDownMs(long ms) {
        coolDownMs = ms;
    }

    /** 设置失败统计窗口（毫秒） */
    public static void setWindowMs(long ms) {
        windowMs = ms;
    }
}
