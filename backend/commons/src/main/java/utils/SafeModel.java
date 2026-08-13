package utils;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模型安全装饰器：限速 + 429/5xx 重试 + 熔断上报
 */
@Slf4j
public class SafeModel implements Model {

    private static final RateLimiter LIMITER = RateLimiter.create(3); // 全局限速器：每秒最多 3 个请求
    private static final int MAX_RETRIES = 3;                          // 429/5xx 最多重试次数
    private static final long RETRY_BASE_MS = 1000;                    // 重试初始等待时间（指数退避）

    private final Model delegate; // 被包装的真实模型

    public SafeModel(Model delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            // 限速：取令牌，超时返回错误流
            if (!LIMITER.tryAcquire(30_000)) {
                return Flux.error(new RuntimeException("API请求过于频繁，限速器等待超时（超过每秒3次配额）"));
            }
            // 429/5xx 指数退避重试
            return attempt(messages, tools, options, 0);
        });
    }

    /** 带重试的调用（retryCount 从 0 开始，最多 MAX_RETRIES 次） */
    private Flux<ChatResponse> attempt(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, int retryCount) {
        return delegate.stream(messages, tools, options)
                .onErrorResume(err -> {
                    String msg = err.getMessage() == null ? "" : err.getMessage();
                    boolean authError = msg.contains("401") || msg.contains("403") || msg.contains("Authentication")
                            || msg.contains("authentication") || msg.contains("invalid api key");
                    if (authError) {
                        // 认证类错误：强制熔断并降级备用模型
                        log.warn("模型认证失败({})，立即强制熔断并降级备用模型：{}", delegate.getModelName(), msg);
                        CircuitBreaker.getInstance().forceOpen();
                        return Flux.error(err);
                    }
                    boolean protocolError = msg.contains("400") || msg.contains("invalid_request_error")
                            || msg.contains("must be a response to") || msg.contains("tool_calls");
                    if (protocolError) {
                        // 协议类错误：不触发熔断
                        log.warn("模型协议错误({})，不触发熔断（协议问题降级无效）：{}", delegate.getModelName(), msg);
                        return Flux.error(err);
                    }
                    boolean retryable = msg.contains("429") || msg.contains("500") || msg.contains("502")
                            || msg.contains("503") || msg.contains("timeout") || msg.contains("Timed out");
                    if (retryable && retryCount < MAX_RETRIES) {
                        long waitMs = RETRY_BASE_MS * (1L << retryCount); // 指数退避 1s,2s,4s
                        log.warn("模型调用失败({})，{}ms后重试({}/{})：{}", delegate.getModelName(), waitMs, retryCount + 1, MAX_RETRIES, msg);
                        return Flux.defer(() -> {
                            try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            return attempt(messages, tools, options, retryCount + 1);
                        });
                    }
                    // 重试耗尽：上报熔断器
                    CircuitBreaker.getInstance().onFailure();
                    log.warn("模型调用最终失败（已上报熔断器）：{}", msg);
                    return Flux.error(err);
                })
                .doOnNext(resp -> CircuitBreaker.getInstance().onSuccess()); // 成功响应时重置熔断计数
    }
}
