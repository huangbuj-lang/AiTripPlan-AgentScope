package tripPlannerAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import utils.AgentPrompts;
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

    /** sysPrompt 从 classpath 的 agent-rules.md 读取，失败回退内置文本 */
    private static String loadSysPrompt() {
        try (var in = TripPlannerAgent.class.getResourceAsStream("/agent-rules.md")) {
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignore) { }
        return "你是TripPlannerAgent，一个专业的行程规划Agent。（agent-rules.md 缺失，请检查 resources）";
    }

    @Bean
    @Scope("prototype") // 每次A2A任务取新实例，避免单例并发冲突
    public ReActAgent getTripPlannerAgent() {

        Toolkit toolkit = new Toolkit();

        // 只暴露综合工具 query_trip（一次调用拿全部行程数据）
        // 模型只调1次即得天气/景点/酒店/美食，不自由ReAct撮合散工具，砍掉多余轮次
        AmapEnhancedToolFactory.register(toolkit, AgentType.TRIP);

        // ★Skill 热更：拉取 Nacos skill 仓库，把技能说明拼进 sysPrompt（失败降级为无skill）
        String skillPrompt = utils.SkillManager.wireAndPromptSafely(toolkit,
                Config.getInt("SKILL_REFRESH_SEC", 30));
        ReActAgent.Builder builder = AgentUtils.getReActAgentBuilder( // 行程规划Agent Builder
                        "TripPlannerAgent",
                        "擅长处理景点行程规划"
                )
                        .memory(new LimitedMemory(Config.getInt("MEMORY_SIZE", 20))) // 限量记忆：单例Agent上下文无限增长会导致状态卡死/大模型400
                        .toolkit(toolkit) // 挂载工具包
                        .sysPrompt(AgentPrompts.tripAgentSystem(loadSysPrompt() + skillPrompt)); // agent-rules.md + Nacos技能清单

        return builder.build();
    }
}
