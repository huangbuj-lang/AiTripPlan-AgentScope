package tripPlannerAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import utils.AgentUtils;
import utils.Config;
import utils.LimitedMemory;
import utils.mcp.AmapEnhancedToolFactory;
import utils.mcp.AmapEnhancedToolFactory.AgentType;

/**
 * 行程规划Agent
 */
@Component
public class TripPlannerAgent {

    /** ★规范外部化：从 classpath 读 agent-rules.md 作为 sysPrompt（改规则只改文档不用重编译）；读取失败回退内置文本 */
    private static String loadSysPrompt() {
        try (var in = TripPlannerAgent.class.getResourceAsStream("/agent-rules.md")) {
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignore) { }
        return "你是TripPlannerAgent，一个专业的行程规划Agent。（agent-rules.md 缺失，请检查 resources）";
    }

    @Bean
    @Scope("prototype") // ★并发支持：每次A2A任务从容器取新实例（官方标准方案，避免单例状态机并发冲突）
    public ReActAgent getTripPlannerAgent() {

        Toolkit toolkit = new Toolkit();

        // ★高德MCP：只暴露综合工具 query_trip（代码写死固定链，一次调用拿全部行程数据）
        // 模型只调1次即得天气/景点/酒店/美食，不自由ReAct撮合散工具，砍掉多余轮次
        AmapEnhancedToolFactory.register(toolkit, AgentType.TRIP);

        ReActAgent.Builder builder = AgentUtils.getReActAgentBuilder( // 行程规划Agent Builder
                        "TripPlannerAgent",
                        "擅长处理景点行程规划"
                )
                        .memory(new LimitedMemory(Config.getInt("MEMORY_SIZE", 20))) // 限量记忆：单例Agent上下文无限增长会导致状态卡死/大模型400
                        .toolkit(toolkit) // 挂载工具包
                        // ★规范外部化：sysPrompt 从 resources/agent-rules.md 读取（内容与原硬编码一致）
                        .sysPrompt(loadSysPrompt());

        return builder.build();
    }
}
