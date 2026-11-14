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
@Component  // @Component
@Slf4j  // @Slf4j
public class RouteMakingAgent {  // 类声明

    /**
     * 作用：从 classpath 的 agent-rules.md 读取路线 Agent 的系统提示词，缺失时回退内置文本
     * 参数：无
     * 返回值：系统提示词文本，永不为 null
     * 异常：无（读取异常内部忽略，走兜底文本）
     */
    private static String loadSysPrompt() {  // loadSysPrompt 签名
        String s = utils.ExternalRules.load("routeMaking-agent-rules.md", "agent-rules.md");  // 依次尝试两个文件名
        if (s != null && !s.isBlank()) return s;  // 读到非空就用
        return "你是RouteMakingAgent，一个专业的路线规划Agent。（agent-rules.md 缺失，请检查 resources）";  // 否则返回内置兜底文本
    }  // 方法结束
    /**
     * 作用：构建路线制定子 Agent（原型作用域，每次 A2A 任务取新实例）：挂载统一路线工具、限量记忆与 Skill 热更说明
     * 参数：无
     * 返回值：配置好的 ReActAgent 实例
     * 异常：无（Skill 接线失败降级为无 skill）
     */
    @Bean  // @Bean
    @Scope("prototype") // 原型作用域：每次任务获取新实例，避免单例Bean并发冲突  // @Scope("prototype")：避免单例 Bean 并发冲突
    public ReActAgent getRouteMakingAgent() {  // 方法签名

        //高德MCP：只暴露路线综合工具
        //跨城自驾 query_route_all/query_route/query_distance + 市内游衔接 query_urban_route（步行/公交）
        Toolkit toolkit = new Toolkit();  // 新建 Toolkit
        AmapEnhancedToolFactory.register(toolkit, AgentType.ROUTE);  // 注册 ROUTE 类型工具集

        Set<String> toolNames = toolkit.getToolNames(); // 打印挂载的工具  // 取挂载的工具名集合（用于启动日志排查）
        log.info("=============");  // 日志分隔线
        toolNames.stream().forEach(  // 逐条打印工具名
                value -> log.info("挂载的工具名称：" + value)  // 参数：工具名
        );  // 打印一行
        log.info("=============");  // 日志分隔线

        //Skill 热更：拉取 Nacos skill 仓库，把技能说明拼进 sysPrompt（失败降级为无skill）
        String skillPrompt = utils.SkillManager.wireAndPromptSafely(toolkit,  // 拉 Nacos 技能并入工具包，返回技能提示词
                Config.getInt("SKILL_REFRESH_SEC", 30));  // 参数：刷新间隔默认 30 秒
        ReActAgent.Builder routeBuilder = AgentUtils.getReActAgentBuilder(  // 构造 Builder
                        "RouteMakingAgent",  // Agent 名 RouteMakingAgent
                        "擅长处理自驾游路线制定"  // 能力描述
                )  // 续行
                        .memory(new LimitedMemory(Config.getInt("MEMORY_SIZE", 20))) // 限量记忆  // 挂限量记忆（默认 20 条）
                        .toolkit(toolkit) // 工具包  // 挂工具包
                        // ★2026-09-11 结果回捞：最终输出按任务指纹存档，主管 A2A 失败时用 /lastResult 回取
                        .hook(new utils.ResultArchiveHook())  // 挂 ResultArchiveHook
                        //规范外部化：sysPrompt 从 resources/agent-rules.md 读取 + 拼接Nacos技能清单
                        .sysPrompt(AgentPrompts.routeAgentSystem(loadSysPrompt() + skillPrompt));  // sysPrompt = routeAgentSystem(agent-rules + skills)
        return routeBuilder.build();  // 构建实例
            }  // 方法结束（缩进比上一行多，格式瑕疵不影响编译）

}  // 类结束

