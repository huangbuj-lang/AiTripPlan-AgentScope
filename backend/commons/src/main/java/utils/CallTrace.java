package utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 任务级调用链追踪：记录单个任务内 A2A/MCP/LLM 各层调用的成败与耗时。 */
public final class CallTrace {  // 类声明：任务级调用链追踪（静态工具）

    /** 单条调用记录 */
    public static class Span {  // 内部类 Span：一条调用记录
        public final long ts = System.currentTimeMillis(); // 发生时间  // ts：发生时间
        public final String layer;   // 层级：A2A / MCP / LLM / TOOL  // layer：层级（A2A / MCP / LLM / TOOL）
        public final String name;    // 名称：行程Agent / maps_geo / deepseek-v4-flash  // name：调用名（如 行程Agent / maps_geo）
        public volatile String detail; // 附加信息：错误码、重试次数、失败原因等（可空，结束时可追加）  // detail：附加信息（错误码/重试次数/失败原因，可追加）
        public volatile long costMs = -1; // 耗时（-1=进行中）  // costMs：耗时（-1=进行中）
        public volatile boolean success;  // 是否成功（叶子=本次调用成败）  // success：本次是否成功
        /** 重试/尝试明细：{n:第几次, ok:是否成功, costMs:耗时, reason:失败原因}，供管理端叶子内下拉展示 */
        public final java.util.List<java.util.Map<String, Object>> attempts = new java.util.ArrayList<>();  // attempts：重试明细列表（第几次/成败/耗时/原因）

        Span(String layer, String name, String detail) {  // Span 构造器
            this.layer = layer; this.name = name; this.detail = detail == null ? "" : detail;  // 保存层级/名称/附加信息（null 归空串）
        }  // 构造器结束

        /**
         * 作用：记录一次尝试明细（A2A 每次发送 / LLM 每次重试），最多保留 10 条防爆
         * 参数：n 第几次尝试；ok 本次是否成功；costMs 本次耗时毫秒；reason 失败原因，null 按空串
         * 返回值：无
         * 异常：无
         */
        public void addAttempt(int n, boolean ok, long costMs, String reason) {  // addAttempt：追加一条尝试明细
            synchronized (attempts) {  // 对 attempts 加锁（并发安全）
                if (attempts.size() >= 10) return; // 防爆  // 最多留 10 条，超出直接丢弃
                java.util.Map<String, Object> m = new java.util.HashMap<>();  // m：一条明细
                m.put("n", n); m.put("ok", ok);  // 第几次 + 是否成功
                m.put("costMs", costMs);  // 耗时
                m.put("reason", reason == null ? "" : reason);  // 失败原因（null 归空串）
                attempts.add(m);  // 加入列表
            }  // synchronized 结束
        }  // 方法结束
    }  // Span 类结束

    private static final int MAX_PER_TASK = 200;  // MAX_PER_TASK：单任务最多留 200 条 span
    /** sessionId -> 调用明细（同步 List：写入频率低，synchronized 足够） */
    private static final ConcurrentHashMap<String, List<Span>> TRACES = new ConcurrentHashMap<>();  // TRACES：会话 → 调用链列表
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private CallTrace() {}  // 私有构造：禁止 new

    /**
     * 作用：开始一次调用并登记到该会话的调用链，返回 span 供结束时回填
     * 参数：sessionId 会话 ID；layer 层级（A2A/MCP/LLM/TOOL）；name 调用名；detail 初始附加信息，可为 null
     * 返回值：新建的 Span；会话已满 200 条时返回的 Span 不入链
     * 异常：无
     */
    public static Span begin(String sessionId, String layer, String name, String detail) {  // begin：开始一次调用并登记
        if (sessionId == null || sessionId.isBlank()) return new Span(layer, name, detail); // 无会话上下文也记录但不入库  // 没有会话上下文：只造 span 不入链
        Span span = new Span(layer, name, detail);  // 新建 span
        List<Span> list = TRACES.computeIfAbsent(sessionId, k -> new ArrayList<>());  // 取（或创建）该会话的链
        synchronized (list) {  // 对链加锁
            list.add(span);  // 加入
            while (list.size() > MAX_PER_TASK) list.remove(0); // 环形截断  // 超过 200 条就淘汰最旧的（环形截断）
        }  // synchronized 结束
        return span;  // 返回 span 供结束时回填
    }  // 方法结束

    /**
     * 作用：向已有 span 追加上下文（错误码、重试次数等）
     * 参数：span 目标 span；more 追加文本
     * 返回值：无
     * 异常：无
     */
    public static void appendDetail(Span span, String more) {  // appendDetail：向 span 追加上下文
        if (span == null || more == null || more.isBlank()) return;  // 任一为空直接返回
        span.detail = span.detail == null || span.detail.isBlank() ? more : span.detail + " " + more;  // 已有内容就空格分隔追加
    }  // 方法结束

    /**
     * 作用：结束 span：写入成败与耗时并打印结束日志
     * 参数：span 目标 span；ok 是否成功；costMs 耗时毫秒
     * 返回值：无
     * 异常：无
     */
    public static void end(Span span, boolean ok, long costMs) {  // end：结束 span，写入成败与耗时
        span.success = ok;  // 成功标志
        span.costMs = costMs;  // 耗时
    }  // 方法结束

    /**
     * 作用：一次性记录一条已完成的调用（不需要 begin/end 的短调用）
     * 参数：sessionId 会话 ID；layer 层级；name 调用名；detail 附加信息；ok 是否成功；costMs 耗时
     * 返回值：无
     * 异常：无
     */
    public static void record(String sessionId, String layer, String name, String detail, boolean ok, long costMs) {  // record：一次性记录已完成调用（免 begin/end）
        if (sessionId == null || sessionId.isBlank()) return;  // 没有会话直接返回
        Span span = begin(sessionId, layer, name, detail);  // 先登记
        end(span, ok, costMs);  // 立刻结束
    }  // 方法结束

    /**
     * 作用：读取某会话的调用链明细
     * 参数：sessionId 会话 ID
     * 返回值：Span 列表（无记录时为空列表）
     * 异常：无
     */
    public static List<Span> trace(String sessionId) {  // trace：读某会话的调用链
        List<Span> list = TRACES.get(sessionId);  // 取列表
        if (list == null) return List.of();  // 没有返回空列表
        synchronized (list) {  // 对链加锁
            List<Span> out = new ArrayList<>(list);  // 复制一份
            java.util.Collections.reverse(out);  // 倒序（最新的在前）
            return out;  // 返回
        }  // synchronized 结束
    }  // 方法结束

    /**
     * 作用：清理除指定会话外的全部调用链，防止内存堆积
     * 参数：keepSessionId 需要保留的会话 ID，null 表示全清
     * 返回值：无
     * 异常：无
     */
    public static void cleanup(String keepSessionId) {  // cleanup：清理其他会话的链（防内存堆积）
        if (TRACES.size() <= 50) return;  // 会话数不多就不用清
        TRACES.keySet().removeIf(k -> !k.equals(keepSessionId));  // 删掉除 keepSessionId 外的全部
    }  // 方法结束

    /**
     * 作用：清空全部会话的调用链（测试/运维用）
     * 参数：无
     * 返回值：无
     * 异常：无
     */
    public static void clear() { TRACES.clear(); }  // clear：清空全部会话的链（测试/运维用）

    /**
     * 作用：把某会话的调用链转成可序列化的 Map 列表（落库/接口输出用）
     * 参数：sessionId 会话 ID
     * 返回值：Map 列表，每项含层级/名称/成败/耗时/重试明细等字段
     * 异常：无
     */
    public static List<Map<String, Object>> traceAsMaps(String sessionId) {  // traceAsMaps：把调用链转成可序列化的 Map 列表
        List<Map<String, Object>> out = new ArrayList<>();  // out：结果列表
        for (Span s : trace(sessionId)) {  // 遍历该会话的 span
            java.util.Map<String, Object> m = new java.util.HashMap<>();  // m：一条记录
            m.put("ts", s.ts); m.put("layer", s.layer); m.put("name", s.name);  // 基础字段：时间/层级/名称
            m.put("detail", s.detail); m.put("costMs", s.costMs); m.put("success", s.success);  // 其余：附加信息/耗时/成败
            if (!s.attempts.isEmpty()) {  // 有重试明细
                synchronized (s.attempts) { m.put("attempts", new ArrayList<>(s.attempts)); }  // 加锁拷贝后放入
            }  // if 结束
            out.add(m);  // 加入结果
        }  // for 结束
        return out;  // 返回
    }  // 方法结束
    /**
     * 作用：向该会话最近一条 LLM span 追加说明（如"生成:xx日游"）
     * 参数：sessionId 会话 ID；more 追加文本
     * 返回值：无
     * 异常：无
     */
    public static void appendLastLlmDetail(String sessionId, String more) {  // appendLastLlmDetail：向最近一条 LLM span 追加说明
        if (sessionId == null || sessionId.isBlank() || more == null || more.isBlank()) return;  // 参数无效直接返回
        List<Span> list = TRACES.get(sessionId);  // 取该会话的链
        if (list == null) return;  // 没有链返回
        synchronized (list) {  // 加锁
            for (int i = list.size() - 1; i >= 0; i--) {  // 从最后往前找
                Span sp = list.get(i);  // 取 span
                if (sp.layer.equals("LLM")) {  // 是 LLM 层
                    appendDetail(sp, more);  // 追加说明
                    return;  // 立即返回
                }  // if 结束
            }  // for 结束
        }  // synchronized 结束
    }  // 方法结束

    /**
     * 作用：清空指定会话的调用链（任务重新提交时用）
     * 参数：sessionId 会话 ID
     * 返回值：无
     * 异常：无
     */
    public static void clear(String sessionId) {  // clear（单会话）：清掉该会话的链（重新提交任务时用）
        if (sessionId == null || sessionId.isBlank()) return;  // 会话为空返回
        TRACES.remove(sessionId);  // 移除
    }  // 方法结束

}  // 类结束
