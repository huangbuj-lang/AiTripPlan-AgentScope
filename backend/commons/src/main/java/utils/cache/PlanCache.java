package utils.cache;

import utils.Config;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 结果缓存（进程内）：缓存专家产物 routeResult + planResult。
 * key = SHA1(目的地|天数|出发地|个人中心沉淀偏好|本次输入偏好|需求指纹)，TTL = PLAN_CACHE_TTL_HOURS（默认 12h）。
 * 需求指纹（TripRequest.cacheFingerprint）覆盖预算/人数/出行类型/约束等进专家 prompt 的字段——
 * 防止"无预算请求先入缓存、带预算请求复用到无预算产物"的串缓存。
 * 零依赖实现（ConcurrentHashMap + 延迟清单），不引入第三方包。
 */
public final class PlanCache {  // 类声明：结果缓存（进程内，零依赖）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private PlanCache() {}  // 私有构造：禁止 new

    public static final class Entry {  // 内部类 Entry：一条缓存记录
        public final String routeResult;  // routeResult：路线专家原文
        public final String planResult;  // planResult：行程专家原文
        public final String candidatesJson; // 三域候选 JSON（空串=旧条目无候选）  // candidatesJson：三域候选 JSON（空串=旧条目无候选）
        public final long expireAtMs;  // expireAtMs：过期时间戳
        /**
         * 作用：构造一条缓存记录（不含三域候选）
         * 参数：routeResult 路线原文；planResult 行程原文；expireAtMs 过期时间戳
         * 返回值：无
         * 异常：无
         */

        public Entry(String routeResult, String planResult, long expireAtMs) {  // 构造器（三参）：不含候选
            this(routeResult, planResult, "", expireAtMs);  // 委托四参构造，候选传空串
        }  // 构造器结束
        /**
         * 作用：构造一条含三域候选的缓存记录（命中缓存时候选直接复用，不再重跑专家）
         * 参数：routeResult 路线原文；planResult 行程原文；candidatesJson 三域候选 JSON；expireAtMs 过期时间戳
         * 返回值：无
         * 异常：无
         */

        public Entry(String routeResult, String planResult, String candidatesJson, long expireAtMs) {  // 构造器（四参）：含三域候选
            this.routeResult = routeResult;  // 保存路线原文
            this.planResult = planResult;  // 保存行程原文
            this.candidatesJson = candidatesJson == null ? "" : candidatesJson;  // 候选 null 归空串
            this.expireAtMs = expireAtMs;  // 保存过期时间
        }  // 构造器结束
    }  // 类结束

    /** key = SHA1(目的地|天数|出发地|个人中心沉淀偏好|本次输入偏好|需求指纹) 的哈希 → Entry（防串缓存）；
     *  需求指纹=cacheFingerprint()，覆盖预算/人数/出行类型/约束等进专家 prompt 但未单独入 key 的字段 */
    private static final ConcurrentMap<String, Entry> CACHE = new ConcurrentHashMap<>();  // CACHE：缓存键 → 缓存记录（并发安全）

    /** 命中/未中计数（进程内存，重启清零——与成本观测页语义一致） */
    private static final java.util.concurrent.atomic.AtomicLong HITS = new java.util.concurrent.atomic.AtomicLong();  // HITS：命中计数（进程内存，重启清零）
    private static final java.util.concurrent.atomic.AtomicLong MISSES = new java.util.concurrent.atomic.AtomicLong();  // MISSES：未中计数

    /** 延时清单：每 5 分钟扫描清过期项 */
    private static final ScheduledExecutorService CLEANER = Executors.newSingleThreadScheduledExecutor(r -> {  // CLEANER：单线程定时清理器
        Thread t = new Thread(r, "PlanCache-Cleaner");  // r：包装成守护线程
        t.setDaemon(true);  // 线程名 PlanCache-Cleaner
        return t;  // 设守护（不阻止进程退出）
    });  // 返回线程
    static {  // 静态初始化块
        CLEANER.scheduleWithFixedDelay(PlanCache::cleanExpired, 5, 5, TimeUnit.MINUTES);  // 每 5 分钟跑一次 cleanExpired（首次延迟 5 分钟）
    }  // 静态块结束
    /**
     * 作用：生成缓存键（目的地/天数/出发地/偏好/指纹五元组拼接后取哈希十六进制）
     * 参数：dest 目的地；days 天数；origin 出发地；storedPrefs 沉淀偏好；pref 文本偏好；fingerprint 行程指纹
     * 返回值：缓存键字符串
     * 异常：无
     */

    private static String key(String dest, int days, String origin, String storedPrefs, String pref, String fingerprint) {  // key：生成缓存键
        StringBuilder sb = new StringBuilder();  // sb：拼接缓冲
        sb.append(dest == null ? "" : dest).append("|")  // 目的地 + 分隔符
          .append(days).append("|")  // 天数
          .append(origin == null ? "" : origin).append("|")  // 出发地
          .append(storedPrefs == null ? "" : storedPrefs).append("|")  // 个人中心沉淀偏好
          .append(pref == null ? "" : pref).append("|")  // 本次输入偏好
          .append(fingerprint == null ? "" : fingerprint);  // 需求指纹（区分同一需求的不同版本）
        return Integer.toHexString(sb.toString().hashCode());  // 取字符串 hashCode 的 16 进制（同输入必同键）
    }  // 方法结束
    /**
     * 作用：清理已过期的缓存条目（定时任务调用）
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    static void cleanExpired() {  // cleanExpired：清理过期条目（定时任务调用）
        long now = System.currentTimeMillis();  // now：当前时间
        CACHE.entrySet().removeIf(e -> e.getValue().expireAtMs <= now);  // 移除所有已过期的条目
    }  // 方法结束

    /**
     * 作用：按需求键查缓存：命中且未过期才返回，并累计命中/未中计数
     * 参数：dest 目的地；days 天数；origin 出发地；storedPrefs 个人中心沉淀偏好文本；pref 用户文本偏好；fingerprint 行程指纹（区分同一需求的不同版本）
     * 返回值：缓存记录；未命中或已过期返回 null
     * 异常：无
     */
    public static Entry get(String dest, int days, String origin, String storedPrefs, String pref, String fingerprint) {  // get：按需求查缓存
        Entry e = CACHE.get(key(dest, days, origin, storedPrefs, pref, fingerprint));  // 按键取记录
        if (e == null) {  // 没取到
            MISSES.incrementAndGet(); // 未中：监控计数  // 未中计数 +1
            return null;  // 返回 null
        }  // if 结束
        if (e.expireAtMs <= System.currentTimeMillis()) {  // 已过期
            CACHE.remove(key(dest, days, origin, storedPrefs, pref, fingerprint));  // 顺手删掉
            MISSES.incrementAndGet(); // 过期视作未中  // 过期算未中
            return null;  // 返回 null
        }  // if 结束
        HITS.incrementAndGet(); // 命中：监控计数  // 命中计数 +1
        return e;  // 返回记录
    }  // 方法结束

    /**
     * 作用：读取缓存统计（条数/命中/未中）
     * 参数：无
     * 返回值：统计 Map
     * 异常：无
     */
    public static Map<String, Object> stats() {  // stats：缓存统计（管理端展示）
        long h = HITS.get(), m = MISSES.get(), tot = h + m;  // h 命中 / m 未中 / tot 总数
        Map<String, Object> r = new java.util.HashMap<>();  // r：结果 Map
        r.put("hits", h);  // 命中次数
        r.put("misses", m);  // 未中次数
        r.put("hitRate", tot == 0 ? 0.0 : Math.round(h * 1000.0 / tot) / 10.0);  // 命中率（百分比，保留一位小数）
        r.put("size", CACHE.size());  // 当前缓存条数
        return r;  // 返回统计
    }  // 方法结束

    /**
     * 作用：写入缓存（不含候选 JSON，内部转调带候选的重载）
     * 参数：dest 目的地；days 天数；origin 出发地；storedPrefs 沉淀偏好；pref 文本偏好；fingerprint 行程指纹；routeResult 路线原文；planResult 行程原文
     * 返回值：无
     * 异常：无
     */
    public static void put(String dest, int days, String origin, String storedPrefs, String pref, String fingerprint,  // put（八参）：不含候选
                           String routeResult, String planResult) {  // 续：routeResult 路线原文、planResult 行程原文
        put(dest, days, origin, storedPrefs, pref, fingerprint, routeResult, planResult, "");  // 委托九参版本，候选传空串
    }  // 方法结束

    /**
     * 作用：写入缓存（含三域候选 JSON），空产物不入缓存防毒缓存，TTL 由 PLAN_CACHE_TTL_HOURS 控制
     * 参数：dest 目的地；days 天数；origin 出发地；storedPrefs 沉淀偏好；pref 文本偏好；fingerprint 行程指纹；routeResult 路线原文；planResult 行程原文；candidatesJson 三域候选 JSON
     * 返回值：无
     * 异常：无
     */
    public static void put(String dest, int days, String origin, String storedPrefs, String pref, String fingerprint,  // put（九参）：含三域候选
                           String routeResult, String planResult, String candidatesJson) {  // 续：candidatesJson 三域候选 JSON
        if (routeResult == null || routeResult.isBlank() || planResult == null || planResult.isBlank()) return; // ★失败产物不入缓存（防毒缓存）  // 产物为空直接不入缓存（防“毒缓存”把失败结果复用出去）
        long ttlMs = TimeUnit.HOURS.toMillis(Config.getInt("PLAN_CACHE_TTL_HOURS", 12));  // ttlMs：从配置 PLAN_CACHE_TTL_HOURS（默认 12 小时）换算毫秒
        CACHE.put(key(dest, days, origin, storedPrefs, pref, fingerprint),  // 写入缓存（键 + 记录，
                new Entry(routeResult, planResult, candidatesJson, System.currentTimeMillis() + ttlMs));  // 续：过期时间 = 现在 + TTL）
    }  // 方法结束
}  // 类结束