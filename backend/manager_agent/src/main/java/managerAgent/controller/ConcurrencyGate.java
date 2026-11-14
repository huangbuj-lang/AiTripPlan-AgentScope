package managerAgent.controller;

import java.util.concurrent.atomic.AtomicInteger;

/** Bounds concurrently running orchestration tasks while allowing runtime retuning. */
final class ConcurrencyGate {
    static final int MIN_LIMIT = 1;
    static final int HARD_LIMIT = 32;

    private final AtomicInteger active = new AtomicInteger();
    private volatile int max;

    ConcurrencyGate(int initialMax) {
        max = normalize(initialMax);
    }

    synchronized boolean tryAcquire() {
        if (active.get() >= max) return false;
        active.incrementAndGet();
        return true;
    }

    void release() {
        active.decrementAndGet();
    }

    void setMax(int value) {
        max = normalize(value);
    }

    int max() {
        return max;
    }

    int active() {
        return active.get();
    }

    private static int normalize(int value) {
        return Math.max(MIN_LIMIT, Math.min(HARD_LIMIT, value));
    }
}
