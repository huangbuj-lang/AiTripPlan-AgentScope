package utils.mcp;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

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
                .onRetryExhaustedThrow((rs, r) -> r.failure()));      // 重试耗尽抛原始异常
    }

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
