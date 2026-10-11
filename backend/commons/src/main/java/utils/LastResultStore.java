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
public final class LastResultStore {

    /** 容量上限，超出后清理最旧的一半，防止长跑进程内存增长 */
    private static final int MAX = 120;

    private static final Map<String, Entry> STORE = new ConcurrentHashMap<>();

    private LastResultStore() {}

    public static class Entry {
        public final String text;
        public final long ts;
        public Entry(String text, long ts) { this.text = text; this.ts = ts; }
    }

    /** 任务指纹：task 文本归一化（去空白）后取摘要，双方一致即可，不需要密码学强度 */
    public static String keyOf(String task) {
        if (task == null) return "";
        String norm = task.replaceAll("\\s+", "").trim();
        if (norm.isEmpty()) return "";
        int h = 0x811c9dc5;
        for (int i = 0; i < norm.length(); i++) {
            h ^= norm.charAt(i);
            h *= 0x01000193;
        }
        return Integer.toHexString(h);
    }

    public static void put(String key, String text) {
        if (key == null || key.isEmpty() || text == null || text.isBlank()) return;
        if (STORE.size() > MAX) {
            STORE.entrySet().stream()
                    .sorted((a, b) -> Long.compare(a.getValue().ts, b.getValue().ts))
                    .limit(MAX / 2)
                    .map(Map.Entry::getKey)
                    .forEach(STORE::remove);
        }
        STORE.put(key, new Entry(text, System.currentTimeMillis()));
    }

    /** 取指定任务指纹的结果（含取走后删除：结果只救一次，避免陈旧数据反复命中） */
    public static Entry take(String key) {
        if (key == null || key.isEmpty()) return null;
        return STORE.remove(key);
    }

    /** 不删的查询（诊断用） */
    public static Entry peek(String key) {
        if (key == null || key.isEmpty()) return null;
        return STORE.get(key);
    }

    /** 指定时间戳之后的最新结果（兜底：指纹对不上时的近似匹配） */
    public static Entry latestSince(long sinceMs) {
        Entry best = null;
        for (Entry e : STORE.values()) {
            if (e.ts >= sinceMs && (best == null || e.ts > best.ts)) best = e;
        }
        return best;
    }

    public static Map<String, Object> stats() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("size", STORE.size());
        r.put("keys", new java.util.ArrayList<>(STORE.keySet()));
        return r;
    }
}
