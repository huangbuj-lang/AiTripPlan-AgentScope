package utils.mcp;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import utils.CallTrace;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 高德MCP限流+重试装饰器：包装真实MCP客户端。 */
public class AmapRateLimitedMcpClientWrapper extends McpClientWrapper {  // 类声明：高德 MCP 客户端的限流 + 重试装饰器

    /** 被包装的真实高德MCP客户端 */
    private final McpClientWrapper delegate;  // delegate：被包装的真实客户端

    /** 最大尝试次数（1次原始 + 2次重试） */
    private static final int MAX_ATTEMPTS = 3;  // MAX_ATTEMPTS：最多 3 次（1 次原始 + 2 次重试）

    /** 重试间隔 */
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);  // RETRY_DELAY：重试基础间隔 1 秒
    /**
     * 作用：包装真实 MCP 客户端，在每次调用前申请全局许可（限并发），并记录按工具名的成功率与耗时
     * 参数：delegate 被包装的真实 MCP 客户端
     * 返回值：无
     * 异常：无
     */

    public AmapRateLimitedMcpClientWrapper(McpClientWrapper delegate) {  // 构造器：绑定真实客户端
        super(delegate.getName());  // 父类需要名称
        this.delegate = delegate;  // 保存 delegate
    }  // 构造器结束
    /**
     * 作用：转发初始化调用
     * 参数：无
     * 返回值：初始化结果流
     * 异常：无
     */
    @Override  // @Override 覆写初始化
    public Mono<Void> initialize() {  // initialize：初始化转发
        return delegate.initialize();  // 交给真实客户端
    }  // 方法结束
    /**
     * 作用：转发工具列表查询
     * 参数：无
     * 返回值：工具列表结果流
     * 异常：无
     */
    @Override  // @Override 覆写工具列表
    public Mono<List<McpSchema.Tool>> listTools() {  // listTools：工具列表转发
        return delegate.listTools();  // 交给真实客户端
    }  // 方法结束
    /**
     * 作用：调用工具：先取高德全局信号量许可 → 执行 → 记录成功/失败与耗时 → 释放许可
     * 参数：name 工具名；arguments 工具参数
     * 返回值：工具调用结果流
     * 异常：无（错误以错误流形式下发；是否可重试由 isRetryable 判定）
     */
    @Override  // @Override 覆写工具调用
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments) {  // callTool：调用工具（限并发 + 重试 + 埋点）
        //用 defer：每次重试都会重新走"acquire->call->release"完整闭环
        return Mono.defer(() ->  // Mono.defer：每次重试都重新走完整闭环
                Mono.fromRunnable(AmapMcpClientFactory::acquire)   // 申请并发许可（排队）  // 先申请并发许可（拿不到就排队等）
                        .then(delegate.callTool(name, arguments))    // 真正调高德  // 再真正调高德
                        .doFinally(s -> AmapMcpClientFactory.release()) // 必释放  // 无论成败都释放许可
        )  // defer 结束
        .retryWhen(Retry.backoff(MAX_ATTEMPTS - 1, RETRY_DELAY)  // 最多重试2次，指数退避  // 重试策略：最多重试 2 次，指数退避
                .maxBackoff(Duration.ofSeconds(3))  // 单次退避上限 3 秒
                .filter(AmapRateLimitedMcpClientWrapper::isRetryable) // 只重试网络类错误  // 只有网络/QPS 类错误才重试
                .onRetryExhaustedThrow((rs, r) -> r.failure()))      // 重试耗尽抛原始异常  // 重试耗尽抛原始异常
        //任务级调用链：MCP 调用成败/耗时/重试次数
        .doOnEach(sig -> {  // 埋点：按信号类型统计
            if (sig.isOnComplete()) {  // 成功完成
                MCP_CALLS.incrementAndGet();  // 总调用数 +1
                recordMcpName(name, true);  // 按工具名记成功
                CallTrace.record(utils.SafeModel.lastTraceId(), "MCP", name,  // 记调用链：MCP 层 + 工具名 +
                        retries.get() > 0 ? "重试" + retries.get() + "次后成功" : "成功", true, elapsed());  // 续：重试次数说明 + 耗时
            } else if (sig.isOnError()) {  // 出错了
                MCP_FAILS.incrementAndGet();  // 失败数 +1
                recordMcpName(name, false);  // 按工具名记失败
                String errMsg = sig.getThrowable() == null ? "" : String.valueOf(sig.getThrowable().getMessage());  // 错误信息（null 归空串）
                CallTrace.record(utils.SafeModel.lastTraceId(), "MCP", name,  // 记调用链：MCP 层 + 工具名 +
                        "失败: " + (errMsg.length() > 120 ? errMsg.substring(0, 120) : errMsg), false, elapsed());  // 续：失败原因（截到 120 字）
            }  // if 结束
        });  // doOnEach 结束
    }  // 方法结束

    //==================== 观测埋点（轻量：进程内存计数 + 耗时） ====================
    public static final java.util.concurrent.atomic.AtomicLong MCP_CALLS = new java.util.concurrent.atomic.AtomicLong(0);  // MCP_CALLS：MCP 调用总数
    public static final java.util.concurrent.atomic.AtomicLong MCP_FAILS = new java.util.concurrent.atomic.AtomicLong(0);  // MCP_FAILS：MCP 失败总数
    /** 按工具名统计：name -> {calls, fails}（管理端子Agent健康页展示高德工具调用成功率） */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> MCP_NAME_STATS = new java.util.concurrent.ConcurrentHashMap<>();  // MCP_NAME_STATS：按工具名的 {调用数, 失败数}

    /**
     * 作用：导出按工具名统计的调用数与失败数（观测用）
     * 参数：无
     * 返回值：Map<工具名, long[]{调用数, 失败数}>
     * 异常：无
     */
    public static java.util.Map<String, long[]> mcpNameStatsSnapshot() {  // mcpNameStatsSnapshot：导出按工具名统计
        java.util.Map<String, long[]> out = new java.util.LinkedHashMap<>();  // out：结果（保序）
        MCP_NAME_STATS.forEach((k, v) -> {  // 遍历统计
            synchronized (v) { out.put(k, new long[]{v[0], v[1]}); }  // 加锁拷贝数组（防并发改）
        });  // forEach 结束
        return out;  // 返回快照
    }  // 方法结束
    /**
     * 作用：记录一次工具调用的成败计数
     * 参数：name 工具名；ok 是否成功
     * 返回值：无
     * 异常：无
     */
    private static void recordMcpName(String name, boolean ok) {  // recordMcpName：记一次调用成败
        long[] s = MCP_NAME_STATS.computeIfAbsent(name, k -> new long[2]);  // 取（或创建）该工具的计数数组
        synchronized (s) {  // 对数组加锁
            s[0]++;  // 调用数 +1
            if (!ok) s[1]++;  // 失败就失败数 +1
        }  // synchronized 结束
    }  // 方法结束
    /** 当前一次调用的重试计数与起始时间（callTool 内单线程语义，简单可变状态即可） */
    private final java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger(0);  // retries：本次调用已重试次数
    private volatile long startMs = 0;  // startMs：本次调用开始时间
    /**
     * 作用：计算本次调用已耗时
     * 参数：无
     * 返回值：耗时毫秒；未开始时返回 -1
     * 异常：无
     */

    private long elapsed() { return startMs == 0 ? -1 : System.currentTimeMillis() - startMs; }  // elapsed：本次调用已耗时（未开始返回 -1）

    /**
     * 作用：读取当前线程绑定的会话 ID（供 MCP 调用链归位）
     * 参数：无
     * 返回值：会话 ID，未设置时为空串
     * 异常：无
     */
    public static String currentSessionId() { return CURRENT_SESSION_ID.get(); }  // currentSessionId：读线程绑定的会话 ID
    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();  // CURRENT_SESSION_ID：线程级会话 ID（供调用链归位）
    /**
     * 作用：设置当前线程的会话 ID
     * 参数：sid 会话 ID
     * 返回值：无
     * 异常：无
     */
    public static void setCurrentSessionId(String sid) { CURRENT_SESSION_ID.set(sid); }  // setCurrentSessionId：设置线程会话 ID

    /**
     * 作用：判断异常是否属于可重试类型（限流/超时/连接重置等）
     * 参数：t 调用抛出的异常
     * 返回值：true=可重试
     * 异常：无
     */
    private static boolean isRetryable(Throwable t) {  // isRetryable：判断异常是否值得重试
        String msg = t == null ? "" : (t.getMessage() == null ? "" : t.getMessage());  // msg：异常信息
        //网络类：连接超时/拒绝/IO/读取失败
        boolean network = msg.contains("connect") || msg.contains("Connect") || msg.contains("timeout")  // 网络类关键字（超时/连接/IO）
                || msg.contains("Timeout") || msg.contains("timed out")  // 续行
                || msg.contains("connection") || msg.contains("Connection")  // 续行
                || msg.contains("IO") || msg.contains("read");  // 续行
        //高德并发/QPS超限：瞬时并发峰值，退避重试可避开
        boolean qps = msg.contains("CUQPS") || msg.contains("EXCEEDED") || msg.contains("QPS")  // 高德并发/QPS 超限关键字
                || msg.contains("RATE_LIMIT") || msg.contains("qps") || msg.contains("rate limit")  // 续行
                || msg.contains("limit") || msg.contains("Limit");  // 续行
        return network || qps;  // 网络或限流类都可重试
    }  // 方法结束
    /**
     * 作用：关闭底层 MCP 客户端
     * 参数：无
     * 返回值：无
     * 异常：无（关闭异常内部捕获）
     */
    @Override  // @Override 覆写关闭
    public void close() {  // close：关闭真实客户端
        delegate.close();  // 转发关闭
    }  // 方法结束
}  // 类结束
