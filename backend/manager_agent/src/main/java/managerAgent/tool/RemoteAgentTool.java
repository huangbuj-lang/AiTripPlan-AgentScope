package managerAgent.tool;

import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import utils.CallTrace;
import managerAgent.hook.EventStore;
import utils.NacosUtil;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将远程Agent封装为工具供主管调用
 */
@Slf4j
public class RemoteAgentTool {

    private String sessionId = "default"; // 会话ID（结果事件按会话隔离）

    /** 设置会话ID */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    // 远程Agent代理缓存：按 name 复用 A2aAgent（避免反复重建 Nacos 连接）
    private static final ConcurrentHashMap<String, A2aAgent> AGENT_CACHE = new ConcurrentHashMap<>();
    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(300); // ★2026-08-24 云端240s→300s: 路线Agent多轮工具调用常超240s

    // ==================== 子Agent健康统计（管理端展示） ====================
    /** 按 agent 名累计：总调用次数 / 失败次数 / 累计耗时ms（进程内存值） */
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> AGENT_CALLS = new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> AGENT_FAILS = new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> AGENT_COST_MS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 记录一次远程调用结果（成功/失败 + 耗时） */
    private static void recordCall(String name, boolean success, long costMs) {
        AGENT_CALLS.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        if (!success) {
            AGENT_FAILS.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        }
        AGENT_COST_MS.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).addAndGet(costMs);
    }

    /** 按 name 获取（缓存复用）A2A 远程Agent代理 */
    private A2aAgent getAgent(String name) throws NacosException {
        A2aAgent agent = AGENT_CACHE.get(name);
        if (agent != null) return agent;
        A2aAgent created = A2aAgent.builder()
                .name(name)
                .agentCardResolver(new NacosAgentCardResolver(NacosUtil.getNacosClient()))
                .build();
        A2aAgent existing = AGENT_CACHE.putIfAbsent(name, created); // 并发下只保留第一个
        return existing != null ? existing : created;
    }

    /** 通用远程Agent调用：查名片 → 发消息 → 阻塞取回复 → 记录事件 */
    private String callRemoteAgent(String name, String task, String label) throws NacosException {
        log.info("============");
        log.info("工具方法：{}...正在调用中", label);
        log.info("============");

        String switchName = "RouteMakingAgent".equals(name) ? "remoteRouteAgent" : "remoteTripAgent";
        if (!utils.AgentRuntimeConfig.toolEnabled(switchName, true)) {
            String result = label + "已通过Nacos开关关闭";
            EventStore.add(sessionId, "Agent结果", label + " ⏸ 已通过Nacos开关关闭");
            log.info("{} disabled by Nacos agent runtime config", label);
            return result;
        }

        Map<String, Object> orchestration = utils.NacosModelConfig.orchestrationConfig();
        RemoteTimeout.configure(orchestration);
        long deadline = System.nanoTime() + RemoteTimeout.timeout().toNanos();

        A2aAgent agent = getAgent(name); // 复用缓存的A2A远程Agent代理

        log.info("============");
        log.info("获取到的远程Agent描述：" + agent.getDescription());
        log.info("============");

        // ★A2A传输级重试：超时/异常/空返回 → 带失败信号重发1次（代码级，与子Agent内工具重试相互独立）
        String emptyMsg0 = label + "没有返回内容";
        String result = null;
        long callStartMs = System.currentTimeMillis(); // 健康统计：整次调用（含重试）计时
        utils.CallTrace.Span a2aSpan = utils.CallTrace.begin(sessionId, "A2A", name, ""); // ★调用链：每轮尝试记入 span.attempts
        for (int attempt = 1; attempt <= 2; attempt++) {
            // ★任务级调用链：会话标记随任务文本传给子Agent，子Agent进程的 LLM/工具 span 据此归位
            String taskToSend = "【trace:" + sessionId + "】" + task;
            if (attempt > 1) {
                taskToSend = task + "\n\n【重试提示】上一次调用超时或返回空。请务必调用你的工具完成查询并输出完整结果。";
                EventStore.add(sessionId, "Agent结果", label + " ⚠️ 第1次调用失败(超时/空)，主管重发(第2次)...");
                log.warn("{} 第1次A2A调用失败(超时/空/异常)，重发中", label);
            }
            try {
                Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
                if (remaining.isNegative() || remaining.isZero()) {
                    result = emptyMsg0;
                    a2aSpan.addAttempt(attempt, false, 0, "剩余超时(超时上限已到)");
                } else {
                    EventStore.setInterruptCallback(sessionId, agent::interrupt);
                    Msg response;
                    try {
                        response = agent.call(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder()
                                                .text(taskToSend)
                                                .build())
                                        .build()
                                ).block(remaining);
                    } finally {
                        EventStore.clearInterruptCallback(sessionId);
                    }
                    result = (response != null && response.getTextContent() != null)
                            ? response.getTextContent()
                            : emptyMsg0;
                    if (result.equals(emptyMsg0)) {
                        a2aSpan.addAttempt(attempt, false, System.currentTimeMillis() - callStartMs, "返回为空(空文本)");
                    } else {
                        a2aSpan.addAttempt(attempt, true, System.currentTimeMillis() - callStartMs, "");
                    }
                }
            } catch (Exception e) {
                // ★用户中断：InterruptedException 或取消标志已置位 → 不重试，直接终止（否则中断会被当成失败重发第2次，永远停不下来）
                if (e instanceof InterruptedException || EventStore.isCancelRequested(sessionId)) {
                    Thread.currentThread().interrupt();
                    a2aSpan.addAttempt(attempt, false, System.currentTimeMillis() - callStartMs, "用户中断");
                    throw new managerAgent.hook.CancelledException();
                }
                log.warn("{} A2A调用异常(attempt={}): {}", label, attempt, e.getMessage());
                String em = e.getMessage() == null ? "" : e.getMessage();
                a2aSpan.addAttempt(attempt, false, System.currentTimeMillis() - callStartMs,
                        em.isBlank() ? "异常" : (em.length() > 120 ? em.substring(0, 120) : em));
                result = emptyMsg0;
            }
            if (result != null && !result.isBlank() && !result.equals(emptyMsg0)) {
                break; // 拿到有效结果
            }
        }
        String emptyMsg = emptyMsg0;
        // 健康统计：按最终结果记录成功/失败与耗时
        boolean callOk = result != null && !result.isBlank() && !result.equals(emptyMsg);
        long callCostMs = System.currentTimeMillis() - callStartMs;
        recordCall(name, callOk, callCostMs);
        // ★任务级调用链：A2A 调用明细（每轮尝试已入 span.attempts，管理端叶子内下拉展示）
        a2aSpan.success = callOk;
        a2aSpan.costMs = callCostMs;
        int failedTries = 0;
        for (java.util.Map<String, Object> at : a2aSpan.attempts) {
            if (!Boolean.TRUE.equals(at.get("ok"))) failedTries++;
        }
        a2aSpan.detail = callOk
                ? (failedTries > 0 ? "子Agent调用成功(重试后)" : "子Agent调用成功")
                : "子Agent调用失败(重试后仍失败)";
        // 记录子Agent调用结果事件
        EventStore.add(sessionId, "Agent结果", result == null || result.isBlank() || result.equals(emptyMsg)
                ? label + " ❌ 调用失败(重试后仍空)"
                : label + " ✅ 调用成功(返回" + result.length() + "字符)");
        log.info("============");
        log.info(label + "返回结果：" + result);
        log.info("============");
        return result;
    }

    /**
     * 调用路线制定Agent，返回规划结果
     */
    @Tool(description = "从Nacos注册中心获取路线制定Agent，返回自驾游路线规划结果")
    public String callRouteMakingAgent(
            @ToolParam(name = "task", description = "用户需要规划的具体自驾路线任务描述，例如：请规划从广州到珠海的自驾路线，包含每日行车路线、途经景点和驾驶时间")
            String task) throws NacosException {
        return callRemoteAgent("RouteMakingAgent", task, "路线Agent");
    }

    /**
     * 调用行程规划Agent，返回规划结果
     */
    @Tool(description = "从Nacos注册中心获取行程规划Agent，返回指定目的地的景点和行程规划结果")
    public String callTripPlannerAgent(
            @ToolParam(name = "task", description = "用户需要规划的具体旅行任务描述，例如：请规划珠海2日游的景点、每日行程安排、酒店和美食推荐")
            String task) throws NacosException {
        return callRemoteAgent("TripPlannerAgent", task, "行程Agent");
    }
}
