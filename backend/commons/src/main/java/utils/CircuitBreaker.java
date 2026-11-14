package utils;

/** 熔断器：连续失败时熔断并降级备用模型 */
public class CircuitBreaker {  // 类声明：熔断器（连续失败降级备用模型）

    /** 熔断器状态 */
    public enum State { CLOSED, OPEN, HALF_OPEN }  // State：三态 CLOSED / OPEN / HALF_OPEN

    private static volatile int failureThreshold = Math.max(1, Config.getInt("BREAKER_FAILURE_THRESHOLD", 5)); // 窗口内失败累计阈值  // failureThreshold：窗口内失败阈值（默认 5）
    private static volatile long windowMs = Config.getLong("BREAKER_WINDOW_MS", 10_000);    // 失败统计窗口（毫秒）  // windowMs：失败统计滑动窗口（默认 10 秒）
    private static volatile long coolDownMs = Config.getLong("BREAKER_COOL_DOWN_MS", 60_000); // 熔断冷却时间（毫秒）  // coolDownMs：熔断冷却时间（默认 60 秒）

    private State state = State.CLOSED;  // state：当前状态
    private final java.util.List<Long> failureTimes = new java.util.ArrayList<>(); // 滑动窗口：记录失败时间戳  // failureTimes：窗口内的失败时间戳
    private long openedAtNanos = 0;                     // 进入 OPEN 的时间  // openedAtNanos：进入 OPEN 的时刻（纳秒）

    private static final CircuitBreaker INSTANCE = new CircuitBreaker();  // INSTANCE：进程级单例
    /**
     * 作用：私有构造，熔断器为进程级单例
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private CircuitBreaker() {}  // 私有构造：单例
    /**
     * 作用：获取进程级熔断器单例（所有模型调用共用同一熔断状态）
     * 参数：无
     * 返回值：CircuitBreaker 单例
     * 异常：无
     */

    public static CircuitBreaker getInstance() {  // getInstance：取单例
        return INSTANCE;  // 返回
    }  // 方法结束

    /**
     * 作用：判断当前是否处于熔断（OPEN）状态；冷却期已过则自动转 HALF_OPEN 放行主模型试探一次
     * 参数：无
     * 返回值：true=仍在熔断期应改用备用模型；false=可走主模型
     * 异常：无
     */
    public synchronized boolean isOpen() {  // isOpen：是否处于熔断期
        if (state == State.CLOSED) {  // CLOSED 状态
            return false;  // 不熔断
        }  // if 结束
        if (state == State.OPEN) {  // OPEN 状态
            //冷却结束：转半开，放行主模型试探
            if (System.nanoTime() - openedAtNanos >= coolDownMs * 1_000_000L) {  // 冷却时间已过
                state = State.HALF_OPEN;  // 转 HALF_OPEN，放行一次试探
                return false; // 半开：允许主模型试探  // 返回 false（可走主模型）
            }  // if 结束
            return true; // 冷却期内：继续熔断  // 仍在冷却期内 → 继续熔断
        }  // if 结束
        //半开状态：允许主模型试探
        return false;  // HALF_OPEN：允许试探
    }  // 方法结束

    /**
     * 作用：记录一次成功调用：清空失败计数，若处于 HALF_OPEN 则恢复为 CLOSED
     * 参数：无
     * 返回值：无
     * 异常：无
     */
    public synchronized void onSuccess() {  // onSuccess：记录成功
        failureTimes.clear();  // 清空失败窗口
        if (state == State.HALF_OPEN) {  // 处于半开
            state = State.CLOSED; // 试探成功：恢复 CLOSED  // 试探成功 → 恢复 CLOSED
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：记录一次失败调用：滑动窗口内失败达到阈值则进入 OPEN；HALF_OPEN 下失败立即重新 OPEN
     * 参数：无
     * 返回值：无
     * 异常：无
     */
    public synchronized void onFailure() {  // onFailure：记录一次失败
        if (state == State.HALF_OPEN) {  // 半开状态下失败
            //半开试探失败：立即重新熔断
            state = State.OPEN;  // 立即重新 OPEN
            openedAtNanos = System.nanoTime();  // 记下熔断时刻
            failureTimes.clear();  // 清空窗口
            failureTimes.add(System.currentTimeMillis());  // 放一条失败（保持窗口非空）
            return;  // 直接返回
        }  // if 结束
        long now = System.currentTimeMillis();  // now：当前时间
        failureTimes.removeIf(t -> now - t >= windowMs);  // 先剔除窗口外的旧失败
        failureTimes.add(now);  // 加入本次失败
        if (failureTimes.size() >= failureThreshold) {  // 失败数达阈值
            state = State.OPEN;  // 进入 OPEN
            openedAtNanos = System.nanoTime();  // 记下熔断时刻
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：立即强制熔断（用于 401/403/402 认证与余额类错误，无需累积阈值），并填满失败窗口
     * 参数：无
     * 返回值：无
     * 异常：无
     */
    public synchronized void forceOpen() {  // forceOpen：立即熔断（401/403/402 这类错误不等阈值）
        state = State.OPEN;  // 直接置 OPEN
        openedAtNanos = System.nanoTime();  // 记熔断时刻
        long now = System.currentTimeMillis();  // now
        failureTimes.clear();  // 清空窗口
        for (int i = 0; i < failureThreshold; i++) failureTimes.add(now);  // 填满阈值条失败（让统计也显示为“已熔断”）
    }  // 方法结束

    /**
     * 作用：读取当前熔断状态
     * 参数：无
     * 返回值：State 枚举（CLOSED/OPEN/HALF_OPEN）
     * 异常：无
     */
    public synchronized State getState() {  // getState：读当前状态
        return state;  // 返回状态
    }  // 方法结束

    /**
     * 作用：读取当前失败统计窗口内的失败次数
     * 参数：无
     * 返回值：窗口内失败条数
     * 异常：无
     */
    public synchronized int getConsecutiveFailures() {  // getConsecutiveFailures：读窗口内失败次数
        return failureTimes.size();  // 返回窗口大小
    }  // 方法结束

    /**
     * 作用：重置熔断器到初始 CLOSED 并清空失败记录（管理端/测试用）
     * 参数：无
     * 返回值：无
     * 异常：无
     */
    public synchronized void reset() {  // reset：重置为 CLOSED
        state = State.CLOSED;  // 状态复位
        failureTimes.clear();  // 清空失败记录
        openedAtNanos = 0;  // 时间归零
    }  // 方法结束

    /**
     * 作用：动态设置失败阈值（Nacos 推送后调用），小于 1 时按 1 处理
     * 参数：n 新的失败次数阈值
     * 返回值：无
     * 异常：无
     */
    public static void setFailureThreshold(int n) {  // setFailureThreshold：热调失败阈值
        failureThreshold = Math.max(1, n);  // 至少为 1
    }  // 方法结束
    /**
     * 作用：读取当前失败阈值
     * 参数：无
     * 返回值：失败次数阈值（默认 5）
     * 异常：无
     */

    public static int getFailureThreshold() {  // getFailureThreshold：读失败阈值
        return failureThreshold;  // 返回
    }  // 方法结束
    /**
     * 作用：读取失败统计滑动窗口长度
     * 参数：无
     * 返回值：窗口毫秒数（默认 10000）
     * 异常：无
     */

    public static long getWindowMs() {  // getWindowMs：读统计窗口
        return windowMs;  // 返回毫秒
    }  // 方法结束
    /**
     * 作用：读取熔断冷却时长
     * 参数：无
     * 返回值：冷却毫秒数（默认 60000）
     * 异常：无
     */

    public static long getCoolDownMs() {  // getCoolDownMs：读冷却时长
        return coolDownMs;  // 返回毫秒
    }  // 方法结束

    /**
     * 作用：设置熔断冷却时长
     * 参数：ms 冷却毫秒数
     * 返回值：无
     * 异常：无
     */
    public static void setCoolDownMs(long ms) {  // setCoolDownMs：设冷却时长
        coolDownMs = ms;  // 赋值
    }  // 方法结束

    /**
     * 作用：设置失败统计滑动窗口长度
     * 参数：ms 窗口毫秒数
     * 返回值：无
     * 异常：无
     */
    public static void setWindowMs(long ms) {  // setWindowMs：设统计窗口
        windowMs = ms;  // 赋值
    }  // 方法结束
}  // 类结束
