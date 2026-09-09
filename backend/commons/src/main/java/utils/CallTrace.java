package utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 任务级调用链追踪：记录单个任务内 A2A/MCP/LLM 各层调用的成败与耗时。 */
public final class CallTrace {

    /** 单条调用记录 */
    public static class Span {
        public final long ts = System.currentTimeMillis(); // 发生时间
        public final String layer;   // 层级：A2A / MCP / LLM / TOOL
        public final String name;    // 名称：行程Agent / maps_geo / deepseek-v4-flash
        public volatile String detail; // 附加信息：错误码、重试次数、失败原因等（可空，结束时可追加）
        public volatile long costMs = -1; // 耗时（-1=进行中）
        public volatile boolean success;  // 是否成功（叶子=本次调用成败）
        /** 重试/尝试明细：{n:第几次, ok:是否成功, costMs:耗时, reason:失败原因}，供管理端叶子内下拉展示 */
        public final java.util.List<java.util.Map<String, Object>> attempts = new java.util.ArrayList<>();

        Span(String layer, String name, String detail) {
            this.layer = layer; this.name = name; this.detail = detail == null ? "" : detail;
        }

        /** 记录一次尝试（A2A 每次发送 / LLM 每次重试） */
        public void addAttempt(int n, boolean ok, long costMs, String reason) {
            synchronized (attempts) {
                if (attempts.size() >= 10) return; // 防爆
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("n", n); m.put("ok", ok);
                m.put("costMs", costMs);
                m.put("reason", reason == null ? "" : reason);
                attempts.add(m);
            }
        }
    }

    private static final int MAX_PER_TASK = 200;
    /** sessionId → 调用明细（同步 List：写入频率低，synchronized 足够） */
    private static final ConcurrentHashMap<String, List<Span>> TRACES = new ConcurrentHashMap<>();

    private CallTrace() {}

    /** 开始一次调用：返回 Span，结束时调 end(span, ok, costMs) */
    public static Span begin(String sessionId, String layer, String name, String detail) {
        if (sessionId == null || sessionId.isBlank()) return new Span(layer, name, detail); // 无会话上下文也记录但不入库
        Span span = new Span(layer, name, detail);
        List<Span> list = TRACES.computeIfAbsent(sessionId, k -> new ArrayList<>());
        synchronized (list) {
            list.add(span);
            while (list.size() > MAX_PER_TASK) list.remove(0); // 环形截断
        }
        return span;
    }

    /** 追加说明（供失败原因/重试信息在结束时补充） */
    public static void appendDetail(Span span, String more) {
        if (span == null || more == null || more.isBlank()) return;
        span.detail = span.detail == null || span.detail.isBlank() ? more : span.detail + " " + more;
    }

    /** 结束一次调用 */
    public static void end(Span span, boolean ok, long costMs) {
        span.success = ok;
        span.costMs = costMs;
    }

    /** 便捷方法：记录一条已完成调用 */
    public static void record(String sessionId, String layer, String name, String detail, boolean ok, long costMs) {
        if (sessionId == null || sessionId.isBlank()) return;
        Span span = begin(sessionId, layer, name, detail);
        end(span, ok, costMs);
    }

    /** 某任务的调用明细快照（新的在前） */
    public static List<Span> trace(String sessionId) {
        List<Span> list = TRACES.get(sessionId);
        if (list == null) return List.of();
        synchronized (list) {
            List<Span> out = new ArrayList<>(list);
            java.util.Collections.reverse(out);
            return out;
        }
    }

        /** 任务结束后清理其明细（防内存累积； */
    public static void cleanup(String keepSessionId) {
        if (TRACES.size() <= 50) return;
        TRACES.keySet().removeIf(k -> !k.equals(keepSessionId));
    }

    /** 测试/运维用：清空全部 */
    public static void clear() { TRACES.clear(); }

    /** 供管理端 JSON 化 */
    public static List<Map<String, Object>> traceAsMaps(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Span s : trace(sessionId)) {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("ts", s.ts); m.put("layer", s.layer); m.put("name", s.name);
            m.put("detail", s.detail); m.put("costMs", s.costMs); m.put("success", s.success);
            if (!s.attempts.isEmpty()) {
                synchronized (s.attempts) { m.put("attempts", new ArrayList<>(s.attempts)); }
            }
            out.add(m);
        }
        return out;
    }
    /** 追加摘要到该任务最近一条 LLM span（解析/分类/聚合完成后回填"做了什么、得到什么"） */
    public static void appendLastLlmDetail(String sessionId, String more) {
        if (sessionId == null || sessionId.isBlank() || more == null || more.isBlank()) return;
        List<Span> list = TRACES.get(sessionId);
        if (list == null) return;
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Span sp = list.get(i);
                if (sp.layer.equals("LLM")) {
                    appendDetail(sp, more);
                    return;
                }
            }
        }
    }

    /** 清空指定任务的调用明细（重新提交同 sessionId 时防新旧混染） */
    public static void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        TRACES.remove(sessionId);
    }

}
