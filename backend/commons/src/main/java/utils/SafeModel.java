package utils;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/** 模型安全装饰器：限速、指数退避重试、熔断时动态切换备用模型。 */
@Slf4j
public class SafeModel implements Model {

    //全局限速器：每秒最多 maxQps 个请求（可通过 Nacos 配置动态调整）
    private static volatile double maxQps = Config.getDouble("MODEL_MAX_QPS", 3);
    private static final RateLimiter LIMITER = RateLimiter.create(maxQps);
    private static volatile int maxRetries = Config.getInt("MODEL_MAX_RETRIES", 3);
    private static volatile long retryBaseMs = Config.getLong("MODEL_RETRY_BASE_MS", 1000);

    //全局在途并发闸：限制"已发出未回完"的模型请求数（防服务端因长连接堆积而 Connection reset）
    //实现：原子计数 + 锁条件等待（比 Semaphore 更适合运行时任意调小上限，不会卡住配置推送线程）
    private static final Object INFLIGHT_LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicInteger INFLIGHT_ACTIVE = new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile int maxInflight = Config.getInt("MODEL_MAX_INFLIGHT", 6);

    /** 动态调整限速（Nacos 推送后调用） */
    public static void setMaxQps(double qps) {
        if (qps <= 0) return;
        maxQps = qps;
        LIMITER.setRate(qps);
    }

    public static double getMaxQps() {
        return maxQps;
    }

    public static int getMaxRetries() {
        return maxRetries;
    }

    public static long getRetryBaseMs() {
        return retryBaseMs;
    }

    public static void setMaxRetries(int n) {
        if (n < 0) return;
        maxRetries = n;
    }

    public static void setRetryBaseMs(long ms) {
        if (ms < 0) return;
        retryBaseMs = ms;
    }

    /** 动态调整在途并发上限（Nacos 推送后调用）：改 volatile 即刻生效，超限的新请求排队等回落 */
    public static void setMaxInflight(int n) {
        if (n < 1) return;
        synchronized (INFLIGHT_LOCK) {
            maxInflight = n;
            INFLIGHT_LOCK.notifyAll(); // 上调时唤醒正在排队等票的请求
        }
    }

    public static int getMaxInflight() {
        return maxInflight;
    }

    /** 当前在途请求数（观测用） */
    public static int getInflightActive() {
        return INFLIGHT_ACTIVE.get();
    }

    /** 申请一个在途名额（阻塞等待，超时抛错）； */
    private static void acquireInflight(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (INFLIGHT_LOCK) {
            while (INFLIGHT_ACTIVE.get() >= maxInflight) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    throw new RuntimeException("在途并发等待超时（上限" + maxInflight + "个请求都在执行中）");
                }
                try {
                    INFLIGHT_LOCK.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("在途并发等待被中断", e);
                }
            }
            INFLIGHT_ACTIVE.incrementAndGet();
        }
    }

    /** 释放一个在途名额； */
    private static void releaseInflight() {
        synchronized (INFLIGHT_LOCK) {
            INFLIGHT_ACTIVE.decrementAndGet();
            INFLIGHT_LOCK.notifyAll();
        }
    }

    private final Model primary;   // 主模型
    private final Model fallback;  // 备用模型（可空）

    //模型调用计数器（观测用）：整个进程累计的模型调用次数（每次ReAct轮=1次）
    public static final java.util.concurrent.atomic.AtomicLong MODEL_CALLS = new java.util.concurrent.atomic.AtomicLong(0);

    //==================== Token/成本统计（观测用，进程内存累计） ====================
    public static final java.util.concurrent.atomic.AtomicLong TOKENS_INPUT = new java.util.concurrent.atomic.AtomicLong(0);
    public static final java.util.concurrent.atomic.AtomicLong TOKENS_OUTPUT = new java.util.concurrent.atomic.AtomicLong(0);
    /** 按维度计的调用次数与token：dimension -> counters（task=规划任务/pipeline=数据管线/forum=论坛AI等） */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> DIM_STATS = new java.util.concurrent.ConcurrentHashMap<>();
    /** 当前线程所属维度（由各入口在调用前设置，stream 内读取； */
    private static final ThreadLocal<String> DIMENSION = ThreadLocal.withInitial(() -> "task");

    /** 会话上下文：主管/TripOrchestrator 在调用前设置，MCP/LLM 的调用链 span 按 sessionId 归位（跨进程各自记录） */
    private static final ThreadLocal<String> CUR_SESSION = new ThreadLocal<>();
    public static void setSessionId(String sid) { CUR_SESSION.set(sid == null ? "" : sid); }
    public static String currentSessionId() { String s = CUR_SESSION.get(); return s == null ? "" : s; }

    // ==================== 错误语义透传（方案B）：最近 LLM 错误 ====================
    /** 按 sessionId 记最近一次 LLM 最终失败原因（格式 epochMs|分类原因）。仅供子Agent端点暴露、manager 侧 A2A 失败时查询 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> LAST_ERROR =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 读取某会话最近一次 LLM 失败原因（无则 null）；子Agent端点 /lastError 直接返回它 */
    public static String lastError(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? null : LAST_ERROR.get(sessionId);
    }

    /** 清除某会话的最近错误（任务成功结束时调用，避免下次误报旧错误） */
    public static void clearLastError(String sessionId) {
        if (sessionId != null) LAST_ERROR.remove(sessionId);
    }

    /** 记录最近错误（防无限增长：超 200 条清理最旧一半） */
    private static void recordLastError(String sid, String reason) {
        if (sid == null || sid.isBlank() || reason == null || reason.isBlank()) return;
        LAST_ERROR.put(sid, System.currentTimeMillis() + "|" + reason);
        if (LAST_ERROR.size() > 200) {
            java.util.List<String> keys = new java.util.ArrayList<>(LAST_ERROR.keySet());
            keys.sort(java.util.Comparator.comparing(k -> LAST_ERROR.getOrDefault(k, "")));
            for (int i = 0; i < keys.size() / 2; i++) LAST_ERROR.remove(keys.get(i));
        }
    }

    /** 当前阶段标注：主管在每次模型调用前设置（解析需求/偏好分类/聚合生成...），LLM span 说明据此描述"在做什么" */
    private static final ThreadLocal<String> CUR_STAGE = new ThreadLocal<>();
    public static void setStage(String stage) { CUR_STAGE.set(stage == null ? "" : stage); }
    public static String currentStage() { String st = CUR_STAGE.get(); return st == null ? "" : st; }

    /** 进程级最近 trace 标记：子Agent进程内由模型消息中的【trace:xxx】提取（A2A任务文本前缀），供工具层 span 归位 */
    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_TRACE =
            new java.util.concurrent.atomic.AtomicReference<>("");
    /** 最近一次从消息中解析到的任务 sessionId（空串=未知） */
    public static String lastTraceId() { return LAST_TRACE.get(); }
    /** 从模型消息中提取【trace:xxx】标记并更新上下文（每次 stream 调用都会经过这里） */
    private static void extractTrace(List<Msg> messages) {
        try {
            if (messages == null) return;
            for (Msg m : messages) {
                String t = m.getTextContent();
                if (t == null) continue;
                java.util.regex.Matcher mt = java.util.regex.Pattern
                        .compile("【trace:([^】]{1,80})】").matcher(t);
                if (mt.find()) {
                    String sid = mt.group(1).trim();
                    CUR_SESSION.set(sid);
                    LAST_TRACE.set(sid);
                    return;
                }
            }
        } catch (Exception ignored) { }
    }

    /** 设置当前线程后续模型调用的统计维度（task/pipeline/forum/moderation等） */
    public static void setDimension(String dim) { DIMENSION.set(dim == null || dim.isBlank() ? "task" : dim); }
    public static String getDimension() { return DIMENSION.get(); }

    /** 指定维度的 token 记账（供跨线程流使用：doOnNext 时 ThreadLocal 已不在调用线程） */
    private static void recordUsageAs(String dim, io.agentscope.core.model.ChatUsage u) {
        if (u == null) return;
        long[] stat = DIM_STATS.computeIfAbsent(dim == null || dim.isBlank() ? "task" : dim, k -> new long[4]);
        synchronized (stat) {
            stat[0]++;
            stat[1] += u.getInputTokens();
            stat[2] += u.getOutputTokens();
        }
        TOKENS_INPUT.addAndGet(u.getInputTokens());
        TOKENS_OUTPUT.addAndGet(u.getOutputTokens());
    }

    /** 单条流结束时的 token 记账（含重试的最终 usage； */
    private static void recordUsage(io.agentscope.core.model.ChatUsage u) {
        if (u == null) return;
        String dim = getDimension();
        long[] stat = DIM_STATS.computeIfAbsent(dim, k -> new long[4]); // calls,inTok,outTok,failCnt
        synchronized (stat) {
            stat[0]++;
            stat[1] += u.getInputTokens();
            stat[2] += u.getOutputTokens();
        }
        TOKENS_INPUT.addAndGet(u.getInputTokens());
        TOKENS_OUTPUT.addAndGet(u.getOutputTokens());
    }

    /** 维度统计快照：{dim: {calls,inTokens,outTokens}}（管理端 /admin/costs 用） */
    /** JSON 友好版：按维度统计(调用数/入tokens/出tokens) -> Map<String, List<Long>> */
    public static java.util.Map<String, java.util.List<Long>> dimensionStatsSnapshotAsMap() {
        java.util.Map<String, java.util.List<Long>> out = new java.util.LinkedHashMap<>();
        for (var e : dimensionStatsSnapshot().entrySet()) {
            long[] v = e.getValue();
            out.put(e.getKey(), java.util.List.of(v[0], v[1], v[2]));
        }
        return out;
    }

    public static java.util.Map<String, long[]> dimensionStatsSnapshot() {
        java.util.Map<String, long[]> out = new java.util.LinkedHashMap<>();
        DIM_STATS.forEach((k, v) -> {
            synchronized (v) { out.put(k, new long[]{v[0], v[1], v[2], v[3]}); }
        });
        return out;
    }

    public SafeModel(Model primary, Model fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String getModelName() {
        return primary.getModelName();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            extractTrace(messages); // 子Agent进程：从任务文本提取【trace:xxx】，让本进程所有 span 归位到正确任务
            //限速：取令牌，超时返回错误流
            if (!LIMITER.tryAcquire(30_000)) {
                return Flux.error(new RuntimeException("API请求过于频繁，限速器等待超时（超过每秒" + maxQps + "次配额）"));
            }
            //每次调用动态选主/备模型（熔断时降级）
            Model target = (fallback != null && CircuitBreaker.getInstance().isOpen()) ? fallback : primary;
            //在途并发闸：拿到名额才发请求（重试期间占同一名额，流终止统一释放）
            acquireInflight(120_000);
            MODEL_CALLS.incrementAndGet(); // 计数：每次模型调用+1（含重试）
            //调用链：记录一次 LLM 调用 span（归到当前 sessionId，跨进程各自记录）
            String sid = currentSessionId();
            utils.CallTrace.Span llmSpan = sid.isBlank() ? null
                    : utils.CallTrace.begin(sid, "LLM", target.getModelName(), currentStage());
            long llmStart = System.currentTimeMillis();
            java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger(0);
            final String dimAtCall = getDimension(); // 调用线程先捕获维度（doOnNext在reactor信号线程跑，ThreadLocal会丢）
            return attempt(target, messages, tools, options, 0, retries, llmSpan, 1)
                    .doOnNext(resp -> recordUsageAs(dimAtCall, resp.getUsage())) // token记账：用捕获维度，每个成功响应块累计
                    .doOnError(err -> { // 失败原因进调用链：区分 余额/认证/限流/5xx/连接重置/超时
                        String reason = classifyLlmError(err) + (retries.get() > 0 ? "（已重试" + retries.get() + "次）" : "");
                        if (llmSpan != null) {
                            utils.CallTrace.appendDetail(llmSpan, reason);
                        }
                        // ★2026-09-11 错误语义透传(方案B)：记一份"最近LLM错误"，供子Agent端点暴露、manager 侧 A2A 失败时查询真因
                        //   背景：402/超时等错误在子Agent进程内被 A2A 框架变成"空响应"，manager 侧只能看到"返回为空"
                        recordLastError(currentSessionId(), reason);
                    })
                    .doFinally(sig -> {
                        releaseInflight();
                        if (llmSpan != null) {
                            if (sig == reactor.core.publisher.SignalType.ON_COMPLETE) {
                                if (retries.get() > 0) {
                                    utils.CallTrace.appendDetail(llmSpan, "重试" + retries.get() + "次后成功");
                                } else if (llmSpan.detail == null || llmSpan.detail.isBlank()) {
                                    utils.CallTrace.appendDetail(llmSpan, "模型调用成功"); // 子Agent无阶段标注时兜底描述
                                }
                            }
                            utils.CallTrace.end(llmSpan, sig == reactor.core.publisher.SignalType.ON_COMPLETE,
                                    System.currentTimeMillis() - llmStart);
                        }
                    }); // 成功/失败/取消都释放名额并唤醒排队者
        });
    }

    /** LLM 失败原因分类（调用链明细展示）：把底层异常转成一眼能看懂的原因。★2026-09-11 改 public：manager 侧 A2A 失败透传复用同一套分类，避免两处各写一份 */
    public static String classifyLlmError(Throwable err) {
        String m = err.getMessage() == null ? "" : err.getMessage();
        return classifyErrorText(m);
    }

    /** LLM/A2A 失败原因分类（纯文本版）：manager 侧 RemoteAgentTool 复用它把 A2A 异常归成可读类别 */
    public static String classifyErrorText(String m) {
        if (m == null) m = "";
        if (m.contains("Insufficient Balance") || m.contains("402")) return "余额不足(402)";
        if (m.contains("401") || m.contains("403") || m.contains("invalid api key") || m.contains("Authentication")) return "认证失败(401/403)";
        if (m.contains("429")) return "被限流(429)";
        if (m.contains("500") || m.contains("502") || m.contains("503")) return "服务端错误(5xx)";
        if (m.contains("Connection reset")) return "连接被重置(Connection reset)";
        if (m.contains("timeout") || m.contains("Timed out") || m.contains("超时")) return "超时";
        if (m.contains("chunked") || m.contains("READING_LENGTH") || m.contains("Broken pipe")) return "连接中断(响应流被切断)";
        if (m.contains("限速") || m.contains("频繁")) return "本地限速等待超时";
        if (m.contains("在途并发")) return "在途并发闸超时";
        return m.isBlank() ? "未知错误" : (m.length() > 80 ? m.substring(0, 80) : m);
    }

    /** 带重试的调用（retryCount 从 0 开始，最多 MAX_RETRIES 次； */
    private Flux<ChatResponse> attempt(Model target, List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, int retryCount,
                                       java.util.concurrent.atomic.AtomicInteger retries,
                                       utils.CallTrace.Span llmSpan, int attemptNo) {
        long attemptStart = System.currentTimeMillis();
        return target.stream(messages, tools, options)
                .doOnNext(resp -> CircuitBreaker.getInstance().onSuccess()) // 成功响应时重置熔断计数
                .doOnComplete(() -> { // 本次尝试成功
                    if (llmSpan != null) {
                        llmSpan.addAttempt(attemptNo, true, System.currentTimeMillis() - attemptStart, "");
                    }
                })
                .onErrorResume(err -> {
                    String msg = err.getMessage() == null ? "" : err.getMessage();
                    if (llmSpan != null) { // 本次尝试失败（含原因）
                        llmSpan.addAttempt(attemptNo, false, System.currentTimeMillis() - attemptStart,
                                msg.length() > 120 ? msg.substring(0, 120) : msg);
                    }
                    boolean authError = msg.contains("401") || msg.contains("403") || msg.contains("Authentication")
                            || msg.contains("authentication") || msg.contains("invalid api key")
                            || msg.contains("402") || msg.contains("Insufficient Balance"); // 余额耗尽也切备用模型
                    if (authError) {
                        //认证类错误：强制熔断并降级备用模型
                        log.warn("模型认证失败({})，立即强制熔断并降级备用模型：{}", target.getModelName(), msg);
                        CircuitBreaker.getInstance().forceOpen();
                        return Flux.error(err);
                    }
                    boolean protocolError = msg.contains("400") || msg.contains("invalid_request_error")
                            || msg.contains("must be a response to") || msg.contains("tool_calls");
                    if (protocolError) {
                        //协议类错误：不触发熔断
                        log.warn("模型协议错误({})，不触发熔断（协议问题降级无效）：{}", target.getModelName(), msg);
                        return Flux.error(err);
                    }
                    boolean retryable = msg.contains("429") || msg.contains("500") || msg.contains("502")
                            || msg.contains("503") || msg.contains("timeout") || msg.contains("Timed out");
                    if (retryable && retryCount < maxRetries) {
                        long waitMs = retryBaseMs * (1L << retryCount); // 指数退避 1s,2s,4s
                        log.warn("模型调用失败({})，{}ms后重试({}/{})：{}", target.getModelName(), waitMs, retryCount + 1, maxRetries, msg);
                        //用 Mono.delay 异步退避，避免 Thread.sleep 阻塞 reactor 线程
                        retries.incrementAndGet();
                        return Mono.delay(Duration.ofMillis(waitMs))
                                .thenMany(Flux.defer(() -> attempt(target, messages, tools, options, retryCount + 1, retries, llmSpan, attemptNo + 1)));
                    }
                    //重试耗尽：上报熔断器
                    CircuitBreaker.getInstance().onFailure();
                    log.warn("模型调用最终失败（已上报熔断器）：{}", msg);
                    return Flux.error(err);
                });
    }
}
