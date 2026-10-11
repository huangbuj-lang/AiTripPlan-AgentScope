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

/** 行程规划Agent */
@Component
public class TripPlannerAgent {

    /** sysPrompt 规则：★2026-09-11 改为优先读工作目录磁盘（agent-rules/tripPlanner-agent-rules.md，改规则免重打包），
     *  回退 classpath 内置（首次自动导出到磁盘），都没有才用内置兜底文本 */
    private static String loadSysPrompt() {
        String s = utils.ExternalRules.load("tripPlanner-agent-rules.md", "agent-rules.md");
        if (s != null && !s.isBlank()) return s;
        return "你是TripPlannerAgent，一个专业的行程规划Agent。（agent-rules.md 缺失，请检查 resources）";
    }

    @Bean
    @Scope("prototype") // 每次A2A任务取新实例，避免单例并发冲突
    public ReActAgent getTripPlannerAgent() {

        Toolkit toolkit = new Toolkit();

        //只暴露综合工具 query_trip（一次调用拿全部行程数据）
        //模型只调1次即得天气/景点/酒店/美食，不自由ReAct撮合散工具，砍掉多余轮次
        AmapEnhancedToolFactory.register(toolkit, AgentType.TRIP);

        //Skill 热更：拉取 Nacos skill 仓库，把技能说明拼进 sysPrompt（失败降级为无skill）
        String skillPrompt = utils.SkillManager.wireAndPromptSafely(toolkit,
                Config.getInt("SKILL_REFRESH_SEC", 30));
        ReActAgent.Builder builder = AgentUtils.getReActAgentBuilder( // 行程规划Agent Builder
                        "TripPlannerAgent",
                        "擅长处理景点行程规划"
                )
                        .memory(new LimitedMemory(Config.getInt("MEMORY_SIZE", 20))) // 限量记忆：单例Agent上下文无限增长会导致状态卡死/大模型400
                        .toolkit(toolkit) // 挂载工具包
                        // ★2026-09-11 结果回捞：把最终输出按任务指纹存档，主管 A2A 失败时用 /lastResult 回取
                        .hook(new utils.ResultArchiveHook())
                        .sysPrompt(AgentPrompts.tripAgentSystem(loadSysPrompt() + skillPrompt)); // agent-rules.md + Nacos技能清单

        return builder.build();
    }
}
