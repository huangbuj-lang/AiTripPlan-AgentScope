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
@Component  // @Component：注册为 Spring 组件
public class TripPlannerAgent {  // 类声明

    /**
     * 作用：从 classpath 的 agent-rules.md 读取行程 Agent 的系统提示词，缺失时回退内置文本
     * 参数：无
     * 返回值：系统提示词文本，永不为 null
     * 异常：无（读取异常内部忽略，走兜底文本）
     */
    private static String loadSysPrompt() {  // loadSysPrompt 签名
        String s = utils.ExternalRules.load("tripPlanner-agent-rules.md", "agent-rules.md");  // 调 ExternalRules.load 依次尝试两个文件名
        if (s != null && !s.isBlank()) return s;  // 读到非空内容就用它
        return "你是TripPlannerAgent，一个专业的行程规划Agent。（agent-rules.md 缺失，请检查 resources）";  // 都没读到：返回内置兜底文本（并提示检查 resources）
    }  // 方法结束
    /**
     * 作用：构建行程规划子 Agent（原型作用域，每次 A2A 任务取新实例）：挂载三域候选工具、限量记忆与 Skill 热更说明
     * 参数：无
     * 返回值：配置好的 ReActAgent 实例
     * 异常：无（Skill 接线失败降级为无 skill）
     */
    @Bean  // @Bean：由 Spring 管理，A2A 服务端按名字取用
    @Scope("prototype") // 每次A2A任务取新实例，避免单例并发冲突  // @Scope("prototype")：每次任务新实例——避免单例 Agent 并发冲突（并发时单例会报 still running）
    public ReActAgent getTripPlannerAgent() {  // 方法签名

        Toolkit toolkit = new Toolkit();  // 新建 Toolkit（工具包）

        //只暴露综合工具 query_trip（一次调用拿全部行程数据）
        //模型只调1次即得天气/景点/酒店/美食，不自由ReAct撮合散工具，砍掉多余轮次
        AmapEnhancedToolFactory.register(toolkit, AgentType.TRIP);  // 注册 TRIP 类型的工具集（内部是 query_trip_candidates 等）

        //Skill 热更：拉取 Nacos skill 仓库，把技能说明拼进 sysPrompt（失败降级为无skill）
        String skillPrompt = utils.SkillManager.wireAndPromptSafely(toolkit,  // wireAndPromptSafely：拉技能并入工具包，返回技能提示词片段（失败返回空串）
                Config.getInt("SKILL_REFRESH_SEC", 30));  // 参数：刷新间隔，默认 30 秒
        ReActAgent.Builder builder = AgentUtils.getReActAgentBuilder( // 行程规划Agent Builder  // 构造 ReActAgent.Builder
                        "TripPlannerAgent",  // Agent 名 TripPlannerAgent
                        "擅长处理景点行程规划"  // 一句话能力描述
                )  // 续行
                        .memory(new LimitedMemory(Config.getInt("MEMORY_SIZE", 20))) // 限量记忆：单例Agent上下文无限增长会导致状态卡死/大模型400  // 挂限量记忆：默认 20 条（单例/无限记忆会导致上下文爆掉、模型 400）
                        .toolkit(toolkit) // 挂载工具包  // 挂工具包
                        // ★2026-09-11 结果回捞：把最终输出按任务指纹存档，主管 A2A 失败时用 /lastResult 回取
                        .hook(new utils.ResultArchiveHook())  // 挂 ResultArchiveHook
                        .sysPrompt(AgentPrompts.tripAgentSystem(loadSysPrompt() + skillPrompt)); // agent-rules.md + Nacos技能清单  // sysPrompt = agent-rules.md + Nacos 技能清单，再由 tripAgentSystem 包一层

        return builder.build();  // 构建 Agent 实例
    }  // 方法结束
}  // 类结束

