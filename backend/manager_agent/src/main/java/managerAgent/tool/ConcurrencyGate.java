package managerAgent.tool;

import java.util.concurrent.atomic.AtomicInteger;

/** 并发闸门：限制同时运行的编排任务数，支持运行时调整（管理端/Nacos）。 */
public final class ConcurrencyGate {  // 类声明：final 类，不允许继承
    public static final int MIN_LIMIT = 1;  // 常量 MIN_LIMIT=1：下限，再小就永远抢不到槽
    public static final int HARD_LIMIT = 32;  // 常量 HARD_LIMIT=32：上限，防止配一个巨大值把模型额度和内存打满

    private final AtomicInteger active = new AtomicInteger();  // active：当前已占用的槽数（AtomicInteger，多线程自增/自减安全）
    private volatile int max;  // max：当前上限；volatile 保证 Nacos 回调线程改了之后其他线程立刻可见
    /**
     * 作用：构造并发闸，初始上限按合法区间夹紧
     * 参数：initialMax 期望的并发上限，会被夹紧到 [MIN_LIMIT, HARD_LIMIT]
     * 返回值：无
     * 异常：无
     */

    public ConcurrencyGate(int initialMax) {  // 构造签名：ConcurrencyGate(int initialMax)
        max = normalize(initialMax);  // max = normalize(initialMax)：先夹紧再赋值，保证初始值一定合法
    }  // 构造结束
    /**
     * 作用：尝试占用一个并发槽：已满则立即返回 false（不排队）
     * 参数：无
     * 返回值：true=占用成功；false=已达上限
     * 异常：无
     */

    public synchronized boolean tryAcquire() {  // synchronized：把“判断 + 自增”做成原子操作，防止两个请求同时通过检查导致超限
        if (active.get() >= max) return false;  // 已占用 >= 上限 → 直接返回 false（这就是“不排队”的落点）
        active.incrementAndGet();  // 没满：计数加一
        return true;  // 返回 true，调用方继续提交任务
    }  // 方法结束
    /**
     * 作用：释放一个并发槽（任务结束必须调用，否则槽位泄漏）
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    public void release() {  // release 签名：不需要 synchronized，单次原子递减就够
        active.decrementAndGet();  // 计数减一
    }  // 方法结束
    /**
     * 作用：运行时调整并发上限（配置热更后调用），夹紧到合法区间
     * 参数：value 新的并发上限
     * 返回值：无
     * 异常：无
     */

    public void setMax(int value) {  // setMax 签名
        max = normalize(value);  // max = normalize(value)：先夹紧再赋值；已经在跑的任务不受影响，只影响后续准入
    }  // 方法结束
    /**
     * 作用：读取当前并发上限
     * 参数：无
     * 返回值：并发上限
     * 异常：无
     */

    public int max() {  // max() 签名
        return max;  // 直接返回 volatile 字段（读到的就是最新值）
    }  // 方法结束
    /**
     * 作用：读取当前占用的槽数
     * 参数：无
     * 返回值：已占用槽数
     * 异常：无
     */

    public int active() {  // active() 签名
        return active.get();  // 返回计数器当前值（管理端监控用）
    }  // 方法结束
    /**
     * 作用：把并发上限夹紧到 [MIN_LIMIT, HARD_LIMIT]
     * 参数：value 待校验的上限
     * 返回值：夹紧后的合法上限
     * 异常：无
     */

    private static int normalize(int value) {  // normalize 签名：静态私有
        return Math.max(MIN_LIMIT, Math.min(HARD_LIMIT, value));  // Math.min(32, value) 先压上限，Math.max(1, …) 再抬下限：配 0/负数→1，配 1000→32，中间原样
    }  // 方法结束
}  // 类结束

