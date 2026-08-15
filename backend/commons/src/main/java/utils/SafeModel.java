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

/**
 * 模型安全装饰器：限速 + 429/5xx 指数退避重试 + 熔断降级
 * ★降级判断下沉到每次调用：熔断时动态切换备用模型（不再是构建时一次性判断）
 */
@Slf4j
public class SafeModel implements Model {

    // 全局限速器：每秒最多 MAX_QPS 个请求（环境变量可配）
    private static final double MAX_QPS = Config.getDouble("MODEL_MAX_QPS", 3);
    private static final RateLimiter LIMITER = RateLimiter.create(MAX_QPS);
    private static final int MAX_RETRIES = Config.getInt("MODEL_MAX_RETRIES", 3);
    private static final long RETRY_BASE_MS = Config.getLong("MODEL_RETRY_BASE_MS", 1000);

    private final Model primary;   // 主模型
    private final Model fallback;  // 备用模型（可空）

    // ★模型调用计数器（观测用）：整个进程累计的模型调用次数（每次ReAct轮=1次）
    public static final java.util.concurrent.atomic.AtomicLong MODEL_CALLS = new java.util.concurrent.atomic.AtomicLong(0);

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
            // 限速：取令牌，超时返回错误流
            if (!LIMITER.tryAcquire(30_000)) {
                return Flux.error(new RuntimeException("API请求过于频繁，限速器等待超时（超过每秒" + MAX_QPS + "次配额）"));
            }
            // 每次调用动态选主/备模型（熔断时降级）
            Model target = (fallback != null && CircuitBreaker.getInstance().isOpen()) ? fallback : primary;
            MODEL_CALLS.incrementAndGet(); // ★计数：每次模型调用+1（含重试）
            return attempt(target, messages, tools, options, 0);
        });
    }

    /** 带重试的调用（retryCount 从 0 开始，最多 MAX_RETRIES 次） */
    private Flux<ChatResponse> attempt(Model target, List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, int retryCount) {
        return target.stream(messages, tools, options)
                .onErrorResume(err -> {
                    String msg = err.getMessage() == null ? "" : err.getMessage();
                    boolean authError = msg.contains("401") || msg.contains("403") || msg.contains("Authentication")
                            || msg.contains("authentication") || msg.contains("invalid api key");
                    if (authError) {
                        // 认证类错误：强制熔断并降级备用模型
                        log.warn("模型认证失败({})，立即强制熔断并降级备用模型：{}", target.getModelName(), msg);
                        CircuitBreaker.getInstance().forceOpen();
                        return Flux.error(err);
                    }
                    boolean protocolError = msg.contains("400") || msg.contains("invalid_request_error")
                            || msg.contains("must be a response to") || msg.contains("tool_calls");
                    if (protocolError) {
                        // 协议类错误：不触发熔断
                        log.warn("模型协议错误({})，不触发熔断（协议问题降级无效）：{}", target.getModelName(), msg);
                        return Flux.error(err);
                    }
                    boolean retryable = msg.contains("429") || msg.contains("500") || msg.contains("502")
                            || msg.contains("503") || msg.contains("timeout") || msg.contains("Timed out");
                    if (retryable && retryCount < MAX_RETRIES) {
                        long waitMs = RETRY_BASE_MS * (1L << retryCount); // 指数退避 1s,2s,4s
                        log.warn("模型调用失败({})，{}ms后重试({}/{})：{}", target.getModelName(), waitMs, retryCount + 1, MAX_RETRIES, msg);
                        // 用 Mono.delay 异步退避，避免 Thread.sleep 阻塞 reactor 线程
                        return Mono.delay(Duration.ofMillis(waitMs))
                                .thenMany(Flux.defer(() -> attempt(target, messages, tools, options, retryCount + 1)));
                    }
                    // 重试耗尽：上报熔断器
                    CircuitBreaker.getInstance().onFailure();
                    log.warn("模型调用最终失败（已上报熔断器）：{}", msg);
                    return Flux.error(err);
                })
                .doOnNext(resp -> CircuitBreaker.getInstance().onSuccess()); // 成功响应时重置熔断计数
    }
}
