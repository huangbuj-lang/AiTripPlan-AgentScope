package utils;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型安全装饰器单元测试
 */
class SafeModelTest {

    /** 可编排错误的桩模型 */
    static class StubModel implements Model {
        final String name;
        final ArrayDeque<RuntimeException> errors = new ArrayDeque<>();
        int callCount = 0;

        StubModel(String name, RuntimeException... errs) {
            this.name = name;
            errors.addAll(Arrays.asList(errs));
        }

        @Override
        public String getModelName() { return name; }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            callCount++;
            RuntimeException e = errors.poll();
            return e != null ? Flux.error(e) : Flux.empty();
        }
    }

    @AfterEach
    void resetBreaker() {
        CircuitBreaker.getInstance().reset();
        CircuitBreaker.setCoolDownMs(60_000);
    }

    @Test
    void getModelNameDelegatesToPrimary() {
        StubModel primary = new StubModel("deepseek");
        SafeModel safe = new SafeModel(primary, null);
        assertEquals("deepseek", safe.getModelName());
    }

    @Test
    void protocolErrorDoesNotRetry() {
        StubModel primary = new StubModel("deepseek", new RuntimeException("400 invalid_request_error"));
        SafeModel safe = new SafeModel(primary, null);
        assertThrows(RuntimeException.class, () -> safe.stream(null, null, null).blockLast());
        assertEquals(1, primary.callCount); // 协议类错误不重试
    }

    @Test
    void authErrorForcesBreakerOpen() {
        StubModel primary = new StubModel("deepseek", new RuntimeException("401 Authentication failed"));
        SafeModel safe = new SafeModel(primary, null);
        assertThrows(RuntimeException.class, () -> safe.stream(null, null, null).blockLast());
        assertTrue(CircuitBreaker.getInstance().isOpen()); // 认证失败立即熔断
        assertEquals(1, primary.callCount);
    }

    @Test
    void retryableErrorRetriesThenFails() {
        StubModel primary = new StubModel("deepseek",
                new RuntimeException("500 Internal Server Error"),
                new RuntimeException("500 Internal Server Error"),
                new RuntimeException("500 Internal Server Error"),
                new RuntimeException("500 Internal Server Error"));
        SafeModel safe = new SafeModel(primary, null);
        // 默认重试3次：1次初始 + 3次重试 = 4次调用（指数退避约7秒）
        assertThrows(RuntimeException.class, () -> safe.stream(null, null, null).blockLast());
        assertEquals(4, primary.callCount);
    }
}
