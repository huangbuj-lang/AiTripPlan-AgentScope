package utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★2026-09-11 结果回捞（方案①，与既有 DomainCandidateService + /candidate-block 同构）：
 * 子Agent 进程内把 Agent 的<b>最终输出</b>按"任务指纹"存档，主管 A2A 调用失败（超时/空返回）时
 * 直连 /lastResult 回取 —— 打中"子Agent 其实跑完了、但回程丢包"这个真实场景，避免瞎重发。
 *
 * <p>为什么按任务指纹而不是 sessionId：一次主管任务会并发多次调同一个子Agent（如天骨架 3 天并行），
 * 只有 sessionId 无法区分是"哪一次调用"的结果，回捞会串天。指纹取自 task 文本，双方用同一算法。
 */
public final class LastResultStore {  // 类声明：结果回捞存储（进程内静态）

    /** 容量上限，超出后清理最旧的一半，防止长跑进程内存增长 */
    private static final int MAX = 120;  // MAX=120 条

    private static final Map<String, Entry> STORE = new ConcurrentHashMap<>();  // STORE：结果键 → 结果记录（ConcurrentHashMap 线程安全）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private LastResultStore() {}  // 私有构造：禁止 new

    public static class Entry {  // 内部类 Entry：一条结果记录
        public final String text;  // text：结果原文
        public final long ts;  // ts：写入时间戳（淘汰时比大小）
        /**
         * 作用：构造一条结果记录
         * 参数：text 结果文本；ts 写入时间戳
         * 返回值：无
         * 异常：无
         */
        public Entry(String text, long ts) { this.text = text; this.ts = ts; }  // Entry 构造（签名与赋值同行）
    }  // 类结束

    /**
     * 作用：把任务描述归一化成结果键（去空白/取指纹）
     * 参数：task 任务文本
     * 返回值：结果键
     * 异常：无
     */
    public static String keyOf(String task) {  // keyOf：把任务文本算成结果键
        if (task == null) return "";  // task 为 null 返回空串
        String norm = task.replaceAll("\\s+", "").trim();  // 去掉所有空白字符（A2A 传输可能改空白）
        if (norm.isEmpty()) return "";  // 归一后为空返回空串
        int h = 0x811c9dc5;  // FNV-1a 哈希初始值
        for (int i = 0; i < norm.length(); i++) {  // 逐字符算
            h ^= norm.charAt(i);  // 异或当前字符
            h *= 0x01000193;  // 乘 FNV 质数
        }  // for 结束
        return Integer.toHexString(h);  // 返回 16 进制字符串（双方同算法才能对上）
    }  // 方法结束
    /**
     * 作用：保存一份任务结果（供网络抖动时前台补取）
     * 参数：key 结果键；text 结果文本
     * 返回值：无
     * 异常：无
     */

    public static void put(String key, String text) {  // put：存档结果
        if (key == null || key.isEmpty() || text == null || text.isBlank()) return;  // 键或文本无效直接返回
        if (STORE.size() > MAX) {  // 超过容量上限
            STORE.entrySet().stream()  // 流式处理全部条目
                    .sorted((a, b) -> Long.compare(a.getValue().ts, b.getValue().ts))  // 按时间戳升序排
                    .limit(MAX / 2)  // 取最旧的一半
                    .map(Map.Entry::getKey)  // 取键
                    .forEach(STORE::remove);  // 逐个删除
        }  // if 结束
        STORE.put(key, new Entry(text, System.currentTimeMillis()));  // 写入新结果（带当前时间）
    }  // 方法结束

    /**
     * 作用：取走结果并从存储中移除（一次性）
     * 参数：key 结果键
     * 返回值：结果记录；不存在返回 null
     * 异常：无
     */
    public static Entry take(String key) {  // take：取走并删除（一次性回捞）
        if (key == null || key.isEmpty()) return null;  // 键为空返回 null
        return STORE.remove(key);  // remove 原子取出
    }  // 方法结束

    /**
     * 作用：查看结果但不移除
     * 参数：key 结果键
     * 返回值：结果记录；不存在返回 null
     * 异常：无
     */
    public static Entry peek(String key) {  // peek：只读不删
        if (key == null || key.isEmpty()) return null;  // 键为空返回 null
        return STORE.get(key);  // 直接 get
    }  // 方法结束

    /**
     * 作用：取指定时间之后最新的一条结果
     * 参数：sinceMs 起始时间戳
     * 返回值：最新结果记录；无则返回 null
     * 异常：无
     */
    public static Entry latestSince(long sinceMs) {  // latestSince：取某时间之后最新的一条
        Entry best = null;  // best 初始 null
        for (Entry e : STORE.values()) {  // 遍历所有结果
            if (e.ts >= sinceMs && (best == null || e.ts > best.ts)) best = e;  // 时间够新且比当前 best 更晚 → 替换
        }  // for 结束
        return best;  // 返回最新（无则 null）
    }  // 方法结束
    /**
     * 作用：读取结果存储的统计（条数等）
     * 参数：无
     * 返回值：统计 Map
     * 异常：无
     */

    public static Map<String, Object> stats() {  // stats：存储统计
        Map<String, Object> r = new LinkedHashMap<>();  // r：结果 Map（保序）
        r.put("size", STORE.size());  // 条数
        r.put("keys", new java.util.ArrayList<>(STORE.keySet()));  // 全部键（便于排查回捞未命中）
        return r;  // 返回统计
    }  // 方法结束
}  // 类结束

