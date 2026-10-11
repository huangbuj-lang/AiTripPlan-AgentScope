package managerAgent.tool;

import java.util.concurrent.atomic.AtomicInteger;

/** 并发闸门：限制同时运行的编排任务数，支持运行时调整（管理端/Nacos）。 */
public final class ConcurrencyGate {
    public static final int MIN_LIMIT = 1;
    public static final int HARD_LIMIT = 32;

    private final AtomicInteger active = new AtomicInteger();
    private volatile int max;

    public ConcurrencyGate(int initialMax) {
        max = normalize(initialMax);
    }

    public synchronized boolean tryAcquire() {
        if (active.get() >= max) return false;
        active.incrementAndGet();
        return true;
    }

    public void release() {
        active.decrementAndGet();
    }

    public void setMax(int value) {
        max = normalize(value);
    }

    public int max() {
        return max;
    }

    public int active() {
        return active.get();
    }

    private static int normalize(int value) {
        return Math.max(MIN_LIMIT, Math.min(HARD_LIMIT, value));
    }
}
