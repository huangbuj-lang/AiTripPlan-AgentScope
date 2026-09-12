package utils.cache;

import utils.Config;

import java.util.Map;
import java.util.concurrent.*;

/** 结果缓存（进程内）：缓存专家产物 routeResult + planResult。 */
public final class PlanCache {

    private PlanCache() {}

    public static final class Entry {
        public final String routeResult;
        public final String planResult;
        public final long expireAtMs;
        public Entry(String routeResult, String planResult, long expireAtMs) {
            this.routeResult = routeResult;
            this.planResult = planResult;
            this.expireAtMs = expireAtMs;
        }
    }

    /** key=dest|days|origin|storedPrefs 的哈希 -> Entry（防串缓存） */
    private static final ConcurrentMap<String, Entry> CACHE = new ConcurrentHashMap<>();

    /** 命中/未中计数（进程内存，重启清零--与成本观测页语义一致） */
    private static final java.util.concurrent.atomic.AtomicLong HITS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong MISSES = new java.util.concurrent.atomic.AtomicLong();

    /** 延时清单：每 5 分钟扫描清过期项 */
    private static final ScheduledExecutorService CLEANER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PlanCache-Cleaner");
        t.setDaemon(true);
        return t;
    });
    static {
        CLEANER.scheduleWithFixedDelay(PlanCache::cleanExpired, 5, 5, TimeUnit.MINUTES);
    }

    private static String key(String dest, int days, String origin, String storedPrefs, String pref) {
        StringBuilder sb = new StringBuilder();
        sb.append(dest == null ? "" : dest).append("|")
          .append(days).append("|")
          .append(origin == null ? "" : origin).append("|")
          .append(storedPrefs == null ? "" : storedPrefs).append("|")
          .append(pref == null ? "" : pref);
        return Integer.toHexString(sb.toString().hashCode());
    }

    static void cleanExpired() {
        long now = System.currentTimeMillis();
        CACHE.entrySet().removeIf(e -> e.getValue().expireAtMs <= now);
    }

    /** 读缓存，未命中或过期返回 null（计数：命中/未中，供监控回显） */
    public static Entry get(String dest, int days, String origin, String storedPrefs, String pref) {
        Entry e = CACHE.get(key(dest, days, origin, storedPrefs, pref));
        if (e == null) {
            MISSES.incrementAndGet(); // 未中：监控计数
            return null;
        }
        if (e.expireAtMs <= System.currentTimeMillis()) {
            CACHE.remove(key(dest, days, origin, storedPrefs, pref));
            MISSES.incrementAndGet(); // 过期视作未中
            return null;
        }
        HITS.incrementAndGet(); // 命中：监控计数
        return e;
    }

    /** 监控快照：命中数/未中数/命中率/当前存量 */
    public static Map<String, Object> stats() {
        long h = HITS.get(), m = MISSES.get(), tot = h + m;
        Map<String, Object> r = new java.util.HashMap<>();
        r.put("hits", h);
        r.put("misses", m);
        r.put("hitRate", tot == 0 ? 0.0 : Math.round(h * 1000.0 / tot) / 10.0);
        r.put("size", CACHE.size());
        return r;
    }

    /** 写缓存（仅当双方产物非空时写入） */
    public static void put(String dest, int days, String origin, String storedPrefs, String pref, String routeResult, String planResult) {
        if (routeResult == null || planResult == null) return;
        long ttlMs = TimeUnit.HOURS.toMillis(Config.getInt("PLAN_CACHE_TTL_HOURS", 12));
        CACHE.put(key(dest, days, origin, storedPrefs, pref), new Entry(routeResult, planResult, System.currentTimeMillis() + ttlMs));
    }
}