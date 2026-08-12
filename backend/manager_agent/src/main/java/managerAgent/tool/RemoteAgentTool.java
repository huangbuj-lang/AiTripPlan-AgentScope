package managerAgent.tool;

import com.alibaba.nacos.api.ai.AiService;
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

/**
 * 将远程Agent封装为工具（返回远程Agent的结果给主管）
 */
@Slf4j
public class RemoteAgentTool {

    private String sessionId = "default"; // ★会话隔离：Agent结果事件写入哪个会话（ManagerAgent注入）

    /** 设置会话ID（ManagerAgent构造时注入，使"Agent结果"事件与思考过程同会话） */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 调用路线制定Agent，返回规划结果
     */
    @Tool(description = "从Nacos注册中心获取路线制定Agent，返回自驾游路线规划结果")
    public String callRouteMakingAgent(
            @ToolParam(name = "task", description = "用户需要规划的具体自驾路线任务描述，例如：请规划从广州到珠海的自驾路线，包含每日行车路线、途经景点和驾驶时间")
            String task) throws NacosException {

        log.info("============");
        log.info("工具方法：路线制定智能体...正在调用中");
        log.info("============");

        A2aAgent agent = A2aAgent.builder() // 创建A2A远程Agent代理（通过Nacos查找名片）
                .name("RouteMakingAgent")
                .agentCardResolver(
                        new NacosAgentCardResolver(NacosUtil.getNacosClient()))
                .build();

        log.info("============");
        log.info("获取到的远程Agent描述：" + agent.getDescription());
        log.info("============");

        // 把任务文本打包成 A2A 消息，发给远程路线 Agent，阻塞等待它干完活，把回复收回来
        Msg response = agent.call(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder()
                                .text(task)
                                .build())
                        .build()
        ).block();

        String result = (response != null && response.getTextContent() != null) // 把远程Agent的结果返回给主管（不再丢弃）
                ? response.getTextContent()
                : "路线Agent没有返回内容";
        // ★部署修复：结果事件（前端思考栏显示子Agent调用状态，替代关闭思考模式后缺失的提示）
        EventStore.add(sessionId, "Agent结果", result == null || result.isBlank() || result.equals("路线Agent没有返回内容")
                ? "路线Agent ❌ 调用失败(返回空)"
                : "路线Agent ✅ 调用成功(返回" + result.length() + "字符)");
        log.info("============");
        log.info("路线Agent返回结果：" + result);
        log.info("============");
        return result;
    }

    /**
     * 调用行程规划Agent，返回规划结果
     */
    @Tool(description = "从Nacos注册中心获取行程规划Agent，返回指定目的地的景点和行程规划结果")
    public String callTripPlannerAgent(
            @ToolParam(name = "task", description = "用户需要规划的具体旅行任务描述，例如：请规划珠海2日游的景点、每日行程安排、酒店和美食推荐")
            String task) throws NacosException {

        A2aAgent agent = A2aAgent.builder() // 创建A2A远程Agent代理
                .name("TripPlannerAgent")
                .agentCardResolver(
                        new NacosAgentCardResolver(NacosUtil.getNacosClient()))
                .build();

        Msg response = agent.call( // 远程调用行程Agent
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder()
                                .text(task)
                                .build())
                        .build()
        ).block();

        String result = (response != null && response.getTextContent() != null) // 把远程Agent的结果返回给主管
                ? response.getTextContent()
                : "行程Agent没有返回内容";
        // ★部署修复：结果事件（前端思考栏显示子Agent调用状态）
        EventStore.add(sessionId, "Agent结果", result == null || result.isBlank() || result.equals("行程Agent没有返回内容")
                ? "行程Agent ❌ 调用失败(返回空)"
                : "行程Agent ✅ 调用成功(返回" + result.length() + "字符)");
        log.info("============");
        log.info("行程Agent返回结果：" + result);
        log.info("============");
        return result;
    }
}
