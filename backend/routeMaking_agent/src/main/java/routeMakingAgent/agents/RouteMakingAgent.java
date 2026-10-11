package routeMakingAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import utils.AgentPrompts;
import utils.AgentUtils;
import utils.Config;
import utils.LimitedMemory;
import utils.mcp.AmapEnhancedToolFactory;
import utils.mcp.AmapEnhancedToolFactory.AgentType;

import java.util.Set;

/** 路线制定Agent */
@Component
@Slf4j
public class RouteMakingAgent {

    /** 规范外部化：★2026-09-11 改为优先读工作目录磁盘（agent-rules/routeMaking-agent-rules.md，改规则免重打包），
     *  回退 classpath 内置（首次自动导出到磁盘），都没有才用内置兜底文本 */
    private static String loadSysPrompt() {
        String s = utils.ExternalRules.load("routeMaking-agent-rules.md", "agent-rules.md");
        if (s != null && !s.isBlank()) return s;
        return "你是RouteMakingAgent，一个专业的路线规划Agent。（agent-rules.md 缺失，请检查 resources）";
    }

    @Bean
    @Scope("prototype") // 原型作用域：每次任务获取新实例，避免单例Bean并发冲突
    public ReActAgent getRouteMakingAgent() {

        //高德MCP：只暴露路线综合工具
        //跨城自驾 query_route_all/query_route/query_distance + 市内游衔接 query_urban_route（步行/公交）
        Toolkit toolkit = new Toolkit();
        AmapEnhancedToolFactory.register(toolkit, AgentType.ROUTE);

        Set<String> toolNames = toolkit.getToolNames(); // 打印挂载的工具
        log.info("=============");
        toolNames.stream().forEach(
                value -> log.info("挂载的工具名称：" + value)
        );
        log.info("=============");

        //Skill 热更：拉取 Nacos skill 仓库，把技能说明拼进 sysPrompt（失败降级为无skill）
        String skillPrompt = utils.SkillManager.wireAndPromptSafely(toolkit,
                Config.getInt("SKILL_REFRESH_SEC", 30));
        ReActAgent.Builder routeBuilder = AgentUtils.getReActAgentBuilder(
                        "RouteMakingAgent",
                        "擅长处理自驾游路线制定"
                )
                        .memory(new LimitedMemory(Config.getInt("MEMORY_SIZE", 20))) // 限量记忆
                        .toolkit(toolkit) // 工具包
                        // ★2026-09-11 结果回捞：最终输出按任务指纹存档，主管 A2A 失败时用 /lastResult 回取
                        .hook(new utils.ResultArchiveHook())
                        //规范外部化：sysPrompt 从 resources/agent-rules.md 读取 + 拼接Nacos技能清单
                        .sysPrompt(AgentPrompts.routeAgentSystem(loadSysPrompt() + skillPrompt));
        return routeBuilder.build();
            }

}
