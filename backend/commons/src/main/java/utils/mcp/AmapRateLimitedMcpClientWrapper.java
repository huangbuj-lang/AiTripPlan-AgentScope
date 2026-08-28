package utils.mcp;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import utils.CallTrace;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ★高德MCP限流+重试装饰器：包装真实MCP客户端。
 * 1. 限流：每次工具调用前后做并发控制（Semaphore，并发≤QPS，超出排队）。
 * 2. 重试：高德MCP连接偶发不稳定（HTTP connect timed out），连接/网络类失败自动重试，
 *    减少"工具调用失败"对行程的影响。非网络错误（如参数错、配额）不重试。
 * 让框架内部的工具调用（模型ReAct触发）也走限流+重试，不用改动框架。
 */
public class AmapRateLimitedMcpClientWrapper extends McpClientWrapper {

    /** 被包装的真实高德MCP客户端 */
    private final McpClientWrapper delegate;

    /** 最大尝试次数（1次原始 + 2次重试） */
    private static final int MAX_ATTEMPTS = 3;

    /** 重试间隔 */
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    public AmapRateLimitedMcpClientWrapper(McpClientWrapper delegate) {
        super(delegate.getName());
        this.delegate = delegate;
    }

    @Override
    public Mono<Void> initialize() {
        return delegate.initialize();
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        return delegate.listTools();
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments) {
        // 用 defer：每次重试都会重新走"acquire→call→release"完整闭环
        return Mono.defer(() ->
                Mono.fromRunnable(AmapMcpClientFactory::acquire)   // 申请并发许可（排队）
                        .then(delegate.callTool(name, arguments))    // 真正调高德
                        .doFinally(s -> AmapMcpClientFactory.release()) // 必释放
        )
        .retryWhen(Retry.backoff(MAX_ATTEMPTS - 1, RETRY_DELAY)  // 最多重试2次，指数退避
                .maxBackoff(Duration.ofSeconds(3))
                .filter(AmapRateLimitedMcpClientWrapper::isRetryable) // 只重试网络类错误
                .onRetryExhaustedThrow((rs, r) -> r.failure()))      // 重试耗尽抛原始异常
        // ★任务级调用链：MCP 调用成败/耗时/重试次数（管理端任务详情展示；无会话上下文时 CallTrace 自动跳过入库）
        .doOnEach(sig -> {
            if (sig.isOnComplete()) {
                MCP_CALLS.incrementAndGet();
                recordMcpName(name, true);
                CallTrace.record(utils.SafeModel.lastTraceId(), "MCP", name,
                        retries.get() > 0 ? "重试" + retries.get() + "次后成功" : "成功", true, elapsed());
            } else if (sig.isOnError()) {
                MCP_FAILS.incrementAndGet();
                recordMcpName(name, false);
                String errMsg = sig.getThrowable() == null ? "" : String.valueOf(sig.getThrowable().getMessage());
                CallTrace.record(utils.SafeModel.lastTraceId(), "MCP", name,
                        "失败: " + (errMsg.length() > 120 ? errMsg.substring(0, 120) : errMsg), false, elapsed());
            }
        });
    }

    // ==================== ★观测埋点（轻量：进程内存计数 + 耗时） ====================
    public static final java.util.concurrent.atomic.AtomicLong MCP_CALLS = new java.util.concurrent.atomic.AtomicLong(0);
    public static final java.util.concurrent.atomic.AtomicLong MCP_FAILS = new java.util.concurrent.atomic.AtomicLong(0);
    /** 按工具名统计：name → {calls, fails}（管理端子Agent健康页展示高德工具调用成功率） */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> MCP_NAME_STATS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 按工具名的调用/失败统计快照：{name: {calls, fails}} */
    public static java.util.Map<String, long[]> mcpNameStatsSnapshot() {
        java.util.Map<String, long[]> out = new java.util.LinkedHashMap<>();
        MCP_NAME_STATS.forEach((k, v) -> {
            synchronized (v) { out.put(k, new long[]{v[0], v[1]}); }
        });
        return out;
    }
    /** 工具名统计计数（在 doOnEach 内调用） */
    private static void recordMcpName(String name, boolean ok) {
        long[] s = MCP_NAME_STATS.computeIfAbsent(name, k -> new long[2]);
        synchronized (s) {
            s[0]++;
            if (!ok) s[1]++;
        }
    }
    /** 当前一次调用的重试计数与起始时间（callTool 内单线程语义，简单可变状态即可） */
    private final java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile long startMs = 0;

    private long elapsed() { return startMs == 0 ? -1 : System.currentTimeMillis() - startMs; }

    /** 会话上下文：由主管通过 RemoteAgentTool 的任务线程传入（子Agent进程内无 sessionId，留空即可） */
    public static String currentSessionId() { return CURRENT_SESSION_ID.get(); }
    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();
    public static void setCurrentSessionId(String sid) { CURRENT_SESSION_ID.set(sid); }

    /**
     * 判断是否可重试：网络连接类失败 + 高德QPS/并发超限 均可重试（带退避避开瞬时峰值）。
     * 参数错、业务逻辑错（如地址无法定位）不重试。
     */
    private static boolean isRetryable(Throwable t) {
        String msg = t == null ? "" : (t.getMessage() == null ? "" : t.getMessage());
        // 网络类：连接超时/拒绝/IO/读取失败
        boolean network = msg.contains("connect") || msg.contains("Connect") || msg.contains("timeout")
                || msg.contains("Timeout") || msg.contains("timed out")
                || msg.contains("connection") || msg.contains("Connection")
                || msg.contains("IO") || msg.contains("read");
        // 高德并发/QPS超限：瞬时并发峰值，退避重试可避开
        boolean qps = msg.contains("CUQPS") || msg.contains("EXCEEDED") || msg.contains("QPS")
                || msg.contains("RATE_LIMIT") || msg.contains("qps") || msg.contains("rate limit")
                || msg.contains("limit") || msg.contains("Limit");
        return network || qps;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
