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
import managerAgent.hook.EventStore;
import utils.NacosUtil;

import java.time.Duration;
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
    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(180); // 远程调用超时

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

        A2aAgent agent = getAgent(name); // 复用缓存的A2A远程Agent代理

        log.info("============");
        log.info("获取到的远程Agent描述：" + agent.getDescription());
        log.info("============");

        // 打包任务为A2A消息并阻塞等待远程回复（带超时）
        Msg response = agent.call(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder()
                                .text(task)
                                .build())
                        .build()
        ).block(REMOTE_TIMEOUT);

        String emptyMsg = label + "没有返回内容";
        String result = (response != null && response.getTextContent() != null)
                ? response.getTextContent()
                : emptyMsg;
        // 记录子Agent调用结果事件
        EventStore.add(sessionId, "Agent结果", result == null || result.isBlank() || result.equals(emptyMsg)
                ? label + " ❌ 调用失败(返回空)"
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
