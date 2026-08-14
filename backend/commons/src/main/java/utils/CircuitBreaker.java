package utils;

/**
 * 熔断器：连续失败时熔断并降级备用模型
 */
public class CircuitBreaker {

    /** 熔断器状态 */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final int FAILURE_THRESHOLD = Math.max(1, Config.getInt("BREAKER_FAILURE_THRESHOLD", 5)); // 窗口内失败累计阈值
    private static long WINDOW_MS = Config.getLong("BREAKER_WINDOW_MS", 10_000);    // 失败统计窗口（毫秒）
    private static long COOL_DOWN_MS = Config.getLong("BREAKER_COOL_DOWN_MS", 60_000); // 熔断冷却时间（毫秒）

    private State state = State.CLOSED;
    private long[] failureTimes = new long[FAILURE_THRESHOLD]; // 滑动窗口：记录失败时间戳
    private int failureCount = 0;                       // 窗口内失败次数
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
            if (System.nanoTime() - openedAtNanos >= COOL_DOWN_MS * 1_000_000L) {
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
        failureCount = 0;
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
            failureCount = 1;
            return;
        }
        long now = System.currentTimeMillis();
        // 移除窗口外的旧失败记录
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

    /** 强制立即熔断（用于认证类错误） */
    public synchronized void forceOpen() {
        state = State.OPEN;
        openedAtNanos = System.nanoTime();
        failureCount = FAILURE_THRESHOLD; // 填满失败计数
    }

    /** 获取当前熔断状态 */
    public synchronized State getState() {
        return state;
    }

    /** 获取窗口内失败次数 */
    public synchronized int getConsecutiveFailures() {
        return failureCount;
    }

    /** 重置熔断器到初始 CLOSED 状态（管理端/测试用） */
    public synchronized void reset() {
        state = State.CLOSED;
        failureCount = 0;
        openedAtNanos = 0;
    }

    /** 设置熔断冷却时间（毫秒） */
    public static void setCoolDownMs(long ms) {
        COOL_DOWN_MS = ms;
    }

    /** 设置失败统计窗口（毫秒） */
    public static void setWindowMs(long ms) {
        WINDOW_MS = ms;
    }
}
