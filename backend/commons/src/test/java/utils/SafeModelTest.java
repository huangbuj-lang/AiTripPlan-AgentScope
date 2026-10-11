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

/** 模型安全装饰器单元测试 */
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
        // ★2026-09-11 测试隔离修复：CallTrace 是进程内静态共享，跨测试残留会导致
        //   “取第一个 LLM span”之类的断言拿到别的测试留下的 span —— 表现为全类跑红、单跑绿。
        //   同理 SafeModel 的 sessionId 是 ThreadLocal，也需复位。
        CallTrace.clear();
        SafeModel.setSessionId("");
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

    /** 观测：LLM 失败时 span 说明里带分类后的原因（余额不足/认证/限流/5xx/连接重置/超时） */
    @Test
    void llmFailSpanCarriesClassifiedReason() {
        SafeModel.setSessionId("test-llm-fail");
        StubModel primary = new StubModel("deepseek", new RuntimeException("Insufficient Balance"));
        SafeModel safe = new SafeModel(primary, null);
        try {
            safe.stream(null, null, null).blockLast();
        } catch (RuntimeException ignored) { }
        List<CallTrace.Span> spans = CallTrace.trace("test-llm-fail");
        assertTrue(spans.stream().anyMatch(sp -> sp.layer.equals("LLM") && !sp.success));
        CallTrace.Span llm = spans.stream().filter(sp -> sp.layer.equals("LLM")).findFirst().orElse(null);
        assertNotNull(llm);
        assertTrue(llm.detail.contains("余额不足(402)"), "应为分类原因，实际: " + llm.detail);
        CallTrace.clear("test-llm-fail");
    }

    /** 观测：重试后成功的 LLM span 说明里标注重试次数 */
    @Test
    void llmRetrySuccessSpanMentionsRetries() {
        SafeModel.setSessionId("test-llm-retry");
        StubModel primary = new StubModel("deepseek",
                new RuntimeException("500 Internal Server Error")); // 第1次失败→重试→第2次成功(空流)
        SafeModel safe = new SafeModel(primary, null);
        safe.stream(null, null, null).blockLast();
        // ★2026-09-11 修 flaky（单跑绿、全类跑红）：span 的 success/detail 由 reactor 的 doFinally
        //   在信号线程上落定，与主线程断言之间存在时序竞态。这里轮询等待落定，最多 ~1s。
        CallTrace.Span llm = null;
        for (int i = 0; i < 50; i++) {
            llm = CallTrace.trace("test-llm-retry").stream()
                    .filter(sp -> sp.layer.equals("LLM")).findFirst().orElse(null);
            if (llm != null && llm.success && llm.detail != null
                    && llm.detail.contains("重试1次后成功")) {
                break;
            }
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertNotNull(llm, "应记录 LLM span");
        assertTrue(llm.success, "span 应标记成功（等待 doFinally 落定后）");
        assertTrue(llm.detail.contains("重试1次后成功"), "实际: " + llm.detail);
        CallTrace.clear("test-llm-retry");
    }

    @Test
    void retryableErrorRetriesThenFails() {
        StubModel primary = new StubModel("deepseek",
                new RuntimeException("500 Internal Server Error"),
                new RuntimeException("500 Internal Server Error"),
                new RuntimeException("500 Internal Server Error"),
                new RuntimeException("500 Internal Server Error"));
        SafeModel safe = new SafeModel(primary, null);
        //默认重试3次：1次初始 + 3次重试 = 4次调用（指数退避约7秒）
        assertThrows(RuntimeException.class, () -> safe.stream(null, null, null).blockLast());
        assertEquals(4, primary.callCount);
    }
}
