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
@Slf4j  // @Slf4j：注入日志对象
public class SafeModel implements Model {  // 类声明：模型安全装饰器（限速 + 重试 + 熔断降级 + 观测）

    //全局限速器：每秒最多 maxQps 个请求（可通过 Nacos 配置动态调整）
    private static volatile double maxQps = Config.getDouble("MODEL_MAX_QPS", 3);  // maxQps：全局每秒最大请求数（默认 3）
    private static final RateLimiter LIMITER = RateLimiter.create(maxQps);  // LIMITER：令牌桶限速器
    private static volatile int maxRetries = Config.getInt("MODEL_MAX_RETRIES", 3);  // maxRetries：最大重试次数（默认 3）
    private static volatile long retryBaseMs = Config.getLong("MODEL_RETRY_BASE_MS", 1000);  // retryBaseMs：重试基础间隔（默认 1000ms）

    //全局在途并发闸：限制"已发出未回完"的模型请求数（防服务端因长连接堆积而 Connection reset）
    //实现：原子计数 + 锁条件等待（比 Semaphore 更适合运行时任意调小上限，不会卡住配置推送线程）
    private static final Object INFLIGHT_LOCK = new Object();  // INFLIGHT_LOCK：在途并发闸的锁对象
    private static final java.util.concurrent.atomic.AtomicInteger INFLIGHT_ACTIVE = new java.util.concurrent.atomic.AtomicInteger(0);  // INFLIGHT_ACTIVE：当前在途请求数
    private static volatile int maxInflight = Config.getInt("MODEL_MAX_INFLIGHT", 6);  // maxInflight：在途并发上限（默认 6）

    /**
     * 作用：动态调整全局限速（Nacos 推送后调用），≤0 时忽略
     * 参数：qps 每秒允许的模型请求数
     * 返回值：无
     * 异常：无
     */
    public static void setMaxQps(double qps) {  // setMaxQps：热调限速速率
        if (qps <= 0) return;  // 非正数忽略
        maxQps = qps;  // 更新速率
        LIMITER.setRate(qps);  // 同步令牌桶
    }  // 方法结束
    /**
     * 作用：读取当前全局限速值
     * 参数：无
     * 返回值：每秒允许的请求数
     * 异常：无
     */

    public static double getMaxQps() {  // getMaxQps：读当前速率
        return maxQps;  // 返回
    }  // 方法结束
    /**
     * 作用：读取当前重试次数上限
     * 参数：无
     * 返回值：最大重试次数（默认 3）
     * 异常：无
     */

    public static int getMaxRetries() {  // getMaxRetries：读最大重试次数
        return maxRetries;  // 返回
    }  // 方法结束
    /**
     * 作用：读取指数退避的基数毫秒
     * 参数：无
     * 返回值：退避基数毫秒（默认 1000，即 1s/2s/4s）
     * 异常：无
     */

    public static long getRetryBaseMs() {  // getRetryBaseMs：读重试基础间隔
        return retryBaseMs;  // 返回
    }  // 方法结束
    /**
     * 作用：动态设置重试次数上限，负数忽略
     * 参数：n 最大重试次数
     * 返回值：无
     * 异常：无
     */

    public static void setMaxRetries(int n) {  // setMaxRetries：热调重试次数
        if (n < 0) return;  // 负数忽略
        maxRetries = n;  // 更新
    }  // 方法结束
    /**
     * 作用：动态设置指数退避基数，负数忽略
     * 参数：ms 退避基数毫秒
     * 返回值：无
     * 异常：无
     */

    public static void setRetryBaseMs(long ms) {  // setRetryBaseMs：热调重试间隔
        if (ms < 0) return;  // 负数忽略
        retryBaseMs = ms;  // 更新
    }  // 方法结束

    /**
     * 作用：动态设置在途并发上限（Nacos 推送后调用），上调时立即唤醒排队中的请求
     * 参数：n 允许的在途请求数，小于 1 时忽略
     * 返回值：无
     * 异常：无
     */
    public static void setMaxInflight(int n) {  // setMaxInflight：热调在途并发上限
        if (n < 1) return;  // 小于 1 忽略
        synchronized (INFLIGHT_LOCK) {  // 加锁
            maxInflight = n;  // 更新上限
            INFLIGHT_LOCK.notifyAll(); // 上调时唤醒正在排队等票的请求  // 唤醒排队等待的请求（上调后能立刻放行）
        }  // synchronized 结束
    }  // 方法结束
    /**
     * 作用：读取在途并发上限
     * 参数：无
     * 返回值：在途并发上限（默认 6）
     * 异常：无
     */

    public static int getMaxInflight() {  // getMaxInflight：读在途并发上限
        return maxInflight;  // 返回
    }  // 方法结束

    /**
     * 作用：读取当前在途请求数（观测用）
     * 参数：无
     * 返回值：正在执行的请求个数
     * 异常：无
     */
    public static int getInflightActive() {  // getInflightActive：读当前在途数
        return INFLIGHT_ACTIVE.get();  // 返回计数
    }  // 方法结束

    /**
     * 作用：申请一个在途名额：已达上限则阻塞等待，超时或中断时抛异常（名额未占用）
     * 参数：timeoutMs 最长等待毫秒数
     * 返回值：无
     * 异常：RuntimeException：等待超时，或等待线程被中断（此时已恢复中断标志）
     */
    private static void acquireInflight(long timeoutMs) {  // acquireInflight：申请一个在途名额（满了就等）
        long deadline = System.currentTimeMillis() + timeoutMs;  // deadline：最晚等到什么时候
        synchronized (INFLIGHT_LOCK) {  // 加锁
            while (INFLIGHT_ACTIVE.get() >= maxInflight) {  // 在途数已达上限
                long wait = deadline - System.currentTimeMillis();  // 剩余可等时间
                if (wait <= 0) {  // 已超时
                    throw new RuntimeException("在途并发等待超时（上限" + maxInflight + "个请求都在执行中）");  // 抛异常（提示上限与在途数）
                }  // if 结束
                try {  // try
                    INFLIGHT_LOCK.wait(wait);  // 等待被唤醒（释放名额或上调上限时）
                } catch (InterruptedException e) {  // 捕获中断
                    Thread.currentThread().interrupt();  // 恢复中断标志
                    throw new RuntimeException("在途并发等待被中断", e);  // 抛异常
                }  // catch 结束
            }  // while 结束
            INFLIGHT_ACTIVE.incrementAndGet();  // 在途数 +1
        }  // synchronized 结束
    }  // 方法结束

    /**
     * 作用：释放在途名额并唤醒排队者；必须在流终止时调用，成功失败都要释放
     * 参数：无
     * 返回值：无
     * 异常：无
     */
    private static void releaseInflight() {  // releaseInflight：释放一个在途名额
        synchronized (INFLIGHT_LOCK) {  // 加锁
            INFLIGHT_ACTIVE.decrementAndGet();  // 在途数 -1
            INFLIGHT_LOCK.notifyAll();  // 唤醒所有排队者
        }  // synchronized 结束
    }  // 方法结束

    private final Model primary;   // 主模型  // primary：主模型
    private final Model fallback;  // 备用模型（可空）  // fallback：备用模型（可空）

    //模型调用计数器（观测用）：整个进程累计的模型调用次数（每次ReAct轮=1次）
    public static final java.util.concurrent.atomic.AtomicLong MODEL_CALLS = new java.util.concurrent.atomic.AtomicLong(0);  // MODEL_CALLS：进程累计模型调用次数

    //==================== Token/成本统计（观测用，进程内存累计） ====================
    public static final java.util.concurrent.atomic.AtomicLong TOKENS_INPUT = new java.util.concurrent.atomic.AtomicLong(0);  // TOKENS_INPUT：累计输入 token
    public static final java.util.concurrent.atomic.AtomicLong TOKENS_OUTPUT = new java.util.concurrent.atomic.AtomicLong(0);  // TOKENS_OUTPUT：累计输出 token
    /** 按维度计的调用次数与token：dimension -> counters（task=规划任务/pipeline=数据管线/forum=论坛AI等） */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> DIM_STATS = new java.util.concurrent.ConcurrentHashMap<>();  // DIM_STATS：按成本维度的统计 {调用数, 输入, 输出, 失败数}
    /** 当前线程所属维度（由各入口在调用前设置，stream 内读取； */
    private static final ThreadLocal<String> DIMENSION = ThreadLocal.withInitial(() -> "task");  // DIMENSION：当前线程的成本维度（默认 task）

    /** 会话上下文：主管/TripOrchestrator 在调用前设置，MCP/LLM 的调用链 span 按 sessionId 归位（跨进程各自记录） */
    private static final ThreadLocal<String> CUR_SESSION = new ThreadLocal<>();  // CUR_SESSION：当前线程的会话 ID
    /**
     * 作用：设置当前线程的会话 ID，后续 LLM/MCP 调用链 span 归到该会话；null 按空串处理
     * 参数：sid 会话 ID
     * 返回值：无
     * 异常：无
     */
    public static void setSessionId(String sid) { CUR_SESSION.set(sid == null ? "" : sid); }  // setSessionId：设置线程会话 ID（null 归空串）
    /**
     * 作用：读取当前线程的会话 ID
     * 参数：无
     * 返回值：会话 ID，未设置时为空串
     * 异常：无
     */
    public static String currentSessionId() { String s = CUR_SESSION.get(); return s == null ? "" : s; }  // currentSessionId：读线程会话 ID（null 归空串）

    // ==================== 错误语义透传（方案B）：最近 LLM 错误 ====================
    /** 按 sessionId 记最近一次 LLM 最终失败原因（格式 epochMs|分类原因）。仅供子Agent端点暴露、manager 侧 A2A 失败时查询 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> LAST_ERROR =  // LAST_ERROR：会话 → 最近一次 LLM 错误
            new java.util.concurrent.ConcurrentHashMap<>();  // 续：会话 → 错误串的并发 Map

    /**
     * 作用：读取某会话最近一次模型调用失败的原因
     * 参数：sessionId 会话 ID
     * 返回值："时间戳|原因" 文本；无记录或会话为空时返回 null
     * 异常：无
     */
    public static String lastError(String sessionId) {  // lastError：读某会话最近一次 LLM 错误
        return sessionId == null || sessionId.isBlank() ? null : LAST_ERROR.get(sessionId);  // 会话为空返回 null，否则返回“时间|原因”
    }  // 方法结束

    /**
     * 作用：清除某会话的最近错误（任务成功结束时调用，避免下次误报旧错误）
     * 参数：sessionId 会话 ID
     * 返回值：无
     * 异常：无
     */
    public static void clearLastError(String sessionId) {  // clearLastError：清除某会话的错误记录
        if (sessionId != null) LAST_ERROR.remove(sessionId);  // 非空才移除
    }  // 方法结束

    /**
     * 作用：记录某会话最近错误；记录超过 200 条时清理最旧的一半，防无限增长
     * 参数：sid 会话 ID；reason 失败原因
     * 返回值：无
     * 异常：无
     */
    private static void recordLastError(String sid, String reason) {  // recordLastError：记录最近一次 LLM 错误
        if (sid == null || sid.isBlank() || reason == null || reason.isBlank()) return;  // 参数无效直接返回
        LAST_ERROR.put(sid, System.currentTimeMillis() + "|" + reason);  // 存“时间戳|原因”（按时间可排序）
        if (LAST_ERROR.size() > 200) {  // 超过 200 条
            java.util.List<String> keys = new java.util.ArrayList<>(LAST_ERROR.keySet());  // 取全部键
            keys.sort(java.util.Comparator.comparing(k -> LAST_ERROR.getOrDefault(k, "")));  // 按键对应的值（时间串）排序
            for (int i = 0; i < keys.size() / 2; i++) LAST_ERROR.remove(keys.get(i));  // 删掉最旧的一半
        }  // if 结束
    }  // 方法结束

    /** 当前阶段标注：主管在每次模型调用前设置（解析需求/偏好分类/聚合生成...），LLM span 说明据此描述"在做什么" */
    private static final ThreadLocal<String> CUR_STAGE = new ThreadLocal<>();  // CUR_STAGE：当前线程的阶段名（调用链展示用）
    /**
     * 作用：设置当前线程的阶段标注（解析需求/偏好分类/聚合生成等），用于调用链展示
     * 参数：stage 阶段名称，null 按空串处理
     * 返回值：无
     * 异常：无
     */
    public static void setStage(String stage) { CUR_STAGE.set(stage == null ? "" : stage); }  // setStage：设置阶段名
    /**
     * 作用：读取当前线程的阶段标注
     * 参数：无
     * 返回值：阶段名称，未设置时为空串
     * 异常：无
     */
    public static String currentStage() { String st = CUR_STAGE.get(); return st == null ? "" : st; }  // currentStage：读阶段名

    /** 进程级最近 trace 标记：子Agent进程内由模型消息中的【trace:xxx】提取（A2A任务文本前缀），供工具层 span 归位 */
    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_TRACE =  // LAST_TRACE：最近一次 traceId（跨方法取用）
            new java.util.concurrent.atomic.AtomicReference<>("");  // 续：初始为空串
    /**
     * 作用：读取进程内最近一次从消息中解析到的任务 sessionId
     * 参数：无
     * 返回值：最近的任务 sessionId，未知时为空串
     * 异常：无
     */
    public static String lastTraceId() { return LAST_TRACE.get(); }  // lastTraceId：读最近 traceId
    /**
     * 作用：从模型消息文本中提取【trace:xxx】标记，把会话 ID 与阶段上下文归位到该任务（子 Agent 跨进程链路同源）
     * 参数：messages 本次调用的消息列表
     * 返回值：无
     * 异常：无（解析过程的所有异常内部吞掉，不影响调用）
     */
    private static void extractTrace(List<Msg> messages) {  // extractTrace：从消息文本里提取【trace:xxx】归位会话
        try {  // try
            if (messages == null) return;  // 消息为空返回
            for (Msg m : messages) {  // 遍历消息
                String t = m.getTextContent();  // 取文本
                if (t == null) continue;  // 文本为空跳过
                java.util.regex.Matcher mt = java.util.regex.Pattern  // 匹配【trace:xxx】标记
                        .compile("【trace:([^】]{1,80})】").matcher(t);  // 续行
                if (mt.find()) {  // 命中
                    String sid = mt.group(1).trim();  // 取 traceId
                    CUR_SESSION.set(sid);  // 绑到当前线程会话
                    LAST_TRACE.set(sid);  // 同时记为最近 traceId
                    return;  // 立即返回
                }  // if 结束
            }  // for 结束
        } catch (Exception ignored) { }  // 捕获异常忽略
    }  // 方法结束

    /**
     * 作用：设置当前线程后续模型调用的统计维度
     * 参数：dim 维度名（task/pipeline/forum/moderation 等），空值归为 task
     * 返回值：无
     * 异常：无
     */
    public static void setDimension(String dim) { DIMENSION.set(dim == null || dim.isBlank() ? "task" : dim); }  // setDimension：设置成本维度（空则 task）
    /**
     * 作用：读取当前线程的统计维度
     * 参数：无
     * 返回值：维度名，默认 task
     * 异常：无
     */
    public static String getDimension() { return DIMENSION.get(); }  // getDimension：读当前维度

    /**
     * 作用：按指定维度记账一次调用的 token 用量（供跨线程的流式回调使用，避免 ThreadLocal 丢失）
     * 参数：dim 统计维度；u 本次调用的 token 用量，可为 null
     * 返回值：无
     * 异常：无
     */
    private static void recordUsageAs(String dim, io.agentscope.core.model.ChatUsage u) {  // recordUsageAs：按指定维度记 token（不依赖 ThreadLocal）
        if (u == null) return;  // 无用量返回
        long[] stat = DIM_STATS.computeIfAbsent(dim == null || dim.isBlank() ? "task" : dim, k -> new long[4]);  // 取（或建）该维度统计数组
        synchronized (stat) {  // 对数组加锁
            stat[0]++;  // 调用数 +1
            stat[1] += u.getInputTokens();  // 输入 token 累加
            stat[2] += u.getOutputTokens();  // 输出 token 累加
        }  // synchronized 结束
        TOKENS_INPUT.addAndGet(u.getInputTokens());  // 全局输入 token 累加
        TOKENS_OUTPUT.addAndGet(u.getOutputTokens());  // 全局输出 token 累加
    }  // 方法结束

    /**
     * 作用：按当前线程维度记账一次调用的 token 用量
     * 参数：u 本次调用的 token 用量，可为 null
     * 返回值：无
     * 异常：无
     */
    private static void recordUsage(io.agentscope.core.model.ChatUsage u) {  // recordUsage：按当前线程维度记 token
        if (u == null) return;  // 无用量返回
        String dim = getDimension();  // 取当前维度
        long[] stat = DIM_STATS.computeIfAbsent(dim, k -> new long[4]); // calls,inTok,outTok,failCnt  // 取（或建）统计数组
        synchronized (stat) {  // 对数组加锁
            stat[0]++;  // 调用数 +1
            stat[1] += u.getInputTokens();  // 输入 token 累加
            stat[2] += u.getOutputTokens();  // 输出 token 累加
        }  // synchronized 结束
        TOKENS_INPUT.addAndGet(u.getInputTokens());  // 全局输入 token 累加
        TOKENS_OUTPUT.addAndGet(u.getOutputTokens());  // 全局输出 token 累加
    }  // 方法结束

    /** 维度统计快照：{dim: {calls,inTokens,outTokens}}（管理端 /admin/costs 用） */
    /**
     * 作用：按维度导出 JSON 友好的统计快照
     * 参数：无
     * 返回值：Map<维度, List<Long>>，元素顺序为 调用数/输入token/输出token
     * 异常：无
     */
    public static java.util.Map<String, java.util.List<Long>> dimensionStatsSnapshotAsMap() {  // dimensionStatsSnapshotAsMap：维度统计转可序列化格式
        java.util.Map<String, java.util.List<Long>> out = new java.util.LinkedHashMap<>();  // out：结果
        for (var e : dimensionStatsSnapshot().entrySet()) {  // 遍历统计快照
            long[] v = e.getValue();  // 取数组
            out.put(e.getKey(), java.util.List.of(v[0], v[1], v[2]));  // 转成 List（calls/in/out）
        }  // for 结束
        return out;  // 返回
    }  // 方法结束
    /**
     * 作用：按维度导出调用次数与 token 累计快照（管理端成本统计用）
     * 参数：无
     * 返回值：Map<维度, long[]{调用数,输入token,输出token,失败数}> 的拷贝
     * 异常：无
     */

    public static java.util.Map<String, long[]> dimensionStatsSnapshot() {  // dimensionStatsSnapshot：维度统计快照
        java.util.Map<String, long[]> out = new java.util.LinkedHashMap<>();  // out：结果
        DIM_STATS.forEach((k, v) -> {  // 遍历统计
            synchronized (v) { out.put(k, new long[]{v[0], v[1], v[2], v[3]}); }  // 加锁拷贝数组（防并发改）
        });  // forEach 结束
        return out;  // 返回
    }  // 方法结束
    /**
     * 作用：构造安全模型装饰器：主模型承担正常调用，备用模型在熔断时接管
     * 参数：primary 主模型；fallback 备用模型，可为 null 表示不做降级
     * 返回值：无
     * 异常：无
     */

    public SafeModel(Model primary, Model fallback) {  // 构造器：绑定主/备模型
        this.primary = primary;  // 保存主模型
        this.fallback = fallback;  // 保存备用模型
    }  // 构造器结束
    /**
     * 作用：返回底层主模型名称（供调用链与日志标识）
     * 参数：无
     * 返回值：主模型名称
     * 异常：无
     */
    @Override  // @Override 取模型名
    public String getModelName() {  // getModelName：
        return primary.getModelName();  // 用主模型的名字
    }  // 方法结束
    /**
     * 作用：模型调用主入口：限速取令牌 → 主/备模型按熔断状态选择 → 申请在途名额 → 带重试发起流式调用，并全程记录调用链 span、token 用量与失败原因
     * 参数：messages 消息列表（系统/用户/历史）；tools 可用工具定义，可为 null；options 生成参数
     * 返回值：Flux<ChatResponse> 响应流；限速等待超时时返回错误流
     * 异常：无（异常以错误流形式下发，由上游 onErrorResume 处理）
     */
    @Override  // @Override 覆写流式调用
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {  // stream：模型调用主入口（限速 → 熔断选模型 → 在途闸 → 重试）
        return Flux.defer(() -> {  // defer：每次订阅都重新走完整流程
            extractTrace(messages); // 子Agent进程：从任务文本提取【trace:xxx】，让本进程所有 span 归位到正确任务  // 先从消息里提取 traceId 归位会话
            //限速：取令牌，超时返回错误流
            if (!LIMITER.tryAcquire(30_000)) {  // 限速：取令牌，最长等 30 秒
                return Flux.error(new RuntimeException("API请求过于频繁，限速器等待超时（超过每秒" + maxQps + "次配额）"));  // 拿不到令牌直接返回错误流
            }  // if 结束
            //每次调用动态选主/备模型（熔断时降级）
            Model target = (fallback != null && CircuitBreaker.getInstance().isOpen()) ? fallback : primary;  // 选模型：熔断打开时用备用模型
            //在途并发闸：拿到名额才发请求（重试期间占同一名额，流终止统一释放）
            acquireInflight(120_000);  // 在途并发闸：拿不到名额就阻塞等待（最多 120 秒）
            MODEL_CALLS.incrementAndGet(); // 计数：每次模型调用+1（含重试）  // 模型调用计数 +1
            //调用链：记录一次 LLM 调用 span（归到当前 sessionId，跨进程各自记录）
            String sid = currentSessionId();  // sid：当前会话
            utils.CallTrace.Span llmSpan = sid.isBlank() ? null  // 有会话才记调用链 span（
                    : utils.CallTrace.begin(sid, "LLM", target.getModelName(), currentStage());  // 续：层级 LLM + 模型名 + 当前阶段）
            long llmStart = System.currentTimeMillis();  // llmStart：开始时间
            java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger(0);  // retries：本次调用的重试计数
            final String dimAtCall = getDimension(); // 调用线程先捕获维度（doOnNext在reactor信号线程跑，ThreadLocal会丢）  // dimAtCall：先在调用线程捕获维度（reactor 信号线程会丢 ThreadLocal）
            return attempt(target, messages, tools, options, 0, retries, llmSpan, 1)  // 开始第一次尝试
                    .doOnNext(resp -> recordUsageAs(dimAtCall, resp.getUsage())) // token记账：用捕获维度，每个成功响应块累计  // 每个成功响应块按捕获的维度记 token
                    .doOnError(err -> { // 失败原因进调用链：区分 余额/认证/限流/5xx/连接重置/超时  // 失败时把原因写进调用链
                        String reason = classifyLlmError(err) + (retries.get() > 0 ? "（已重试" + retries.get() + "次）" : "");  // 归因（已重试 N 次）
                        if (llmSpan != null) {  // 有 span
                            utils.CallTrace.appendDetail(llmSpan, reason);  // 追加失败原因
                        }  // if 结束
                        // ★2026-09-11 错误语义透传(方案B)：记一份"最近LLM错误"，供子Agent端点暴露、manager 侧 A2A 失败时查询真因
                        //   背景：402/超时等错误在子Agent进程内被 A2A 框架变成"空响应"，manager 侧只能看到"返回为空"
                        recordLastError(currentSessionId(), reason);  // 记一份“最近 LLM 错误”（供 A2A 失败时查真因）
                    })  // doOnError 结束
                    .doFinally(sig -> {  // doFinally：无论成功失败取消都要收尾
                        releaseInflight();  // 释放在途名额（唤醒排队者）
                        if (llmSpan != null) {  // 有 span
                            if (sig == reactor.core.publisher.SignalType.ON_COMPLETE) {  // 是正常完成
                                if (retries.get() > 0) {  // 有过重试
                                    utils.CallTrace.appendDetail(llmSpan, "重试" + retries.get() + "次后成功");  // 追加“重试 N 次后成功”
                                } else if (llmSpan.detail == null || llmSpan.detail.isBlank()) {  // 否则没有其他说明
                                    utils.CallTrace.appendDetail(llmSpan, "模型调用成功"); // 子Agent无阶段标注时兜底描述  // 追加“模型调用成功”
                                }  // if 结束
                            }  // if 结束
                            utils.CallTrace.end(llmSpan, sig == reactor.core.publisher.SignalType.ON_COMPLETE,  // 结束 span：成败 + 耗时
                                    System.currentTimeMillis() - llmStart);  // 续行
                        }  // if 结束
                    }); // 成功/失败/取消都释放名额并唤醒排队者  // doFinally 结束
        });  // defer 结束
    }  // 方法结束

    /**
     * 作用：把底层异常文案归类成可读的失败原因，用于调用链明细展示
     * 参数：err 模型调用抛出的异常
     * 返回值：中文原因标签（余额不足/认证失败/被限流/服务端错误/连接重置/超时等），无法识别时返回截断后的原始信息
     * 异常：无
     */
    public static String classifyLlmError(Throwable err) {  // classifyLlmError：异常归因（对外入口）
        String m = err.getMessage() == null ? "" : err.getMessage();  // 取异常信息
        return classifyErrorText(m);  // 交给文本归因
    }  // 方法结束

    /**
     * 作用：把模型错误文案归类成可读原因（含连接中断类错误）
     * 参数：m 原始错误信息，可为 null
     * 返回值：中文原因标签；无法识别时返回截断后的原文
     * 异常：无
     */
    public static String classifyErrorText(String m) {  // classifyErrorText：按错误文本归因
        if (m == null) m = "";  // null 归空串
        if (m.contains("Insufficient Balance") || m.contains("402")) return "余额不足(402)";  // 余额不足
        if (m.contains("401") || m.contains("403") || m.contains("invalid api key") || m.contains("Authentication")) return "认证失败(401/403)";  // 认证失败
        if (m.contains("429")) return "被限流(429)";  // 被限流
        if (m.contains("500") || m.contains("502") || m.contains("503")) return "服务端错误(5xx)";  // 服务端错误
        if (m.contains("Connection reset")) return "连接被重置(Connection reset)";  // 连接被重置
        if (m.contains("timeout") || m.contains("Timed out") || m.contains("超时")) return "超时";  // 超时
        if (m.contains("chunked") || m.contains("READING_LENGTH") || m.contains("Broken pipe")) return "连接中断(响应流被切断)";  // 响应流被切断
        if (m.contains("限速") || m.contains("频繁")) return "本地限速等待超时";  // 本地限速等待超时
        if (m.contains("在途并发")) return "在途并发闸超时";  // 在途并发闸超时
        return m.isBlank() ? "未知错误" : (m.length() > 80 ? m.substring(0, 80) : m);  // 兜底：原文（截 80 字）
    }  // 方法结束

    /**
     * 作用：单次尝试（含递归重试）：成功时复位熔断计数并记录尝试明细；失败按错误类型分流——认证/余额类强制熔断降级、协议类不熔断、限流与 5xx/超时可重试，退避 1s/2s/4s 后递归
     * 参数：target 本次要调用的模型（主或备）；messages/tools/options 同 stream；retryCount 已重试次数；retries 重试计数累加器；llmSpan 调用链 span；attemptNo 当前尝试序号
     * 返回值：Flux<ChatResponse>；重试耗尽或不可重试时返回错误流
     * 异常：无（所有异常转成错误流，最终失败会上报熔断器）
     */
    private Flux<ChatResponse> attempt(Model target, List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, int retryCount,  // attempt：单次尝试（含指数退避重试）
                                       java.util.concurrent.atomic.AtomicInteger retries,  // 续：retries 重试计数、llmSpan 调用链、attemptNo 第几次
                                       utils.CallTrace.Span llmSpan, int attemptNo) {  // 续行
        long attemptStart = System.currentTimeMillis();  // attemptStart：本次开始时间
        return target.stream(messages, tools, options)  // 真正发起流式调用
                .doOnNext(resp -> CircuitBreaker.getInstance().onSuccess()) // 成功响应时重置熔断计数  // 成功响应就重置熔断计数
                .doOnComplete(() -> { // 本次尝试成功  // 本次尝试正常完成
                    if (llmSpan != null) {  // 有 span
                        llmSpan.addAttempt(attemptNo, true, System.currentTimeMillis() - attemptStart, "");  // 记一条成功尝试（第几次/耗时）
                    }  // if 结束
                })  // doOnComplete 结束
                .onErrorResume(err -> {  // 出错时决定是否重试
                    String msg = err.getMessage() == null ? "" : err.getMessage();  // msg：错误信息
                    if (llmSpan != null) { // 本次尝试失败（含原因）  // 有 span
                        llmSpan.addAttempt(attemptNo, false, System.currentTimeMillis() - attemptStart,  // 记一条失败尝试（第几次/耗时/原因）
                                msg.length() > 120 ? msg.substring(0, 120) : msg);  // 续：原因截 120 字
                    }  // if 结束
                    boolean authError = msg.contains("401") || msg.contains("403") || msg.contains("Authentication")  // 认证/余额类错误判定
                            || msg.contains("authentication") || msg.contains("invalid api key")  // 续行
                            || msg.contains("402") || msg.contains("Insufficient Balance"); // 余额耗尽也切备用模型  // 续：余额耗尽也切备用模型
                    if (authError) {  // 是认证类
                        //认证类错误：强制熔断并降级备用模型
                        log.warn("模型认证失败({})，立即强制熔断并降级备用模型：{}", target.getModelName(), msg);  // warn 日志
                        CircuitBreaker.getInstance().forceOpen();  // 强制熔断（立即切备用模型）
                        return Flux.error(err);  // 返回错误（不重试）
                    }  // if 结束
                    boolean protocolError = msg.contains("400") || msg.contains("invalid_request_error")  // 协议类错误判定（tool_calls 配对等）
                            || msg.contains("must be a response to") || msg.contains("tool_calls");  // 续行
                    if (protocolError) {  // 是协议错误
                        //协议类错误：不触发熔断
                        log.warn("模型协议错误({})，不触发熔断（协议问题降级无效）：{}", target.getModelName(), msg);  // warn 日志
                        return Flux.error(err);  // 返回错误（不重试）
                    }  // if 结束
                    boolean retryable = msg.contains("429") || msg.contains("500") || msg.contains("502")  // 可重试类（限流/5xx/超时）
                            || msg.contains("503") || msg.contains("timeout") || msg.contains("Timed out");  // 续行
                    if (retryable && retryCount < maxRetries) {  // 可重试且未耗尽次数
                        long waitMs = retryBaseMs * (1L << retryCount); // 指数退避 1s,2s,4s  // 指数退避：1s、2s、4s…
                        log.warn("模型调用失败({})，{}ms后重试({}/{})：{}", target.getModelName(), waitMs, retryCount + 1, maxRetries, msg);  // warn 日志：多久后重试第几次
                        //用 Mono.delay 异步退避，避免 Thread.sleep 阻塞 reactor 线程
                        retries.incrementAndGet();  // 重试计数 +1
                        return Mono.delay(Duration.ofMillis(waitMs))  // 用 Mono.delay 异步等待（不阻塞 reactor 线程）
                                .thenMany(Flux.defer(() -> attempt(target, messages, tools, options, retryCount + 1, retries, llmSpan, attemptNo + 1)));  // 等待后递归调用自身（次数+1）
                    }  // if 结束
                    //重试耗尽：上报熔断器
                    CircuitBreaker.getInstance().onFailure();  // 重试耗尽：上报熔断器
                    log.warn("模型调用最终失败（已上报熔断器）：{}", msg);  // warn 日志
                    return Flux.error(err);  // 返回错误
                });  // onErrorResume 结束
    }  // 方法结束
}  // 类结束
