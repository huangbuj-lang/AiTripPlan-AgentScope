package routeMakingAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import utils.AgentUtils;
import utils.Config;
import utils.LimitedMemory;
import utils.mcp.AmapEnhancedToolFactory;
import utils.mcp.AmapEnhancedToolFactory.AgentType;

import java.util.Set;

/**
 * 路线制定Agent
 */
@Component
@Slf4j
public class RouteMakingAgent {

    /** ★规范外部化：从 classpath 读 agent-rules.md 作为 sysPrompt（改规则只改文档不用重编译）；读取失败回退内置提示 */
    private static String loadSysPrompt() {
        try (var in = RouteMakingAgent.class.getResourceAsStream("/agent-rules.md")) {
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignore) { }
        return "你是RouteMakingAgent，一个专业的路线规划Agent。（agent-rules.md 缺失，请检查 resources）";
    }

    @Bean
    @Scope("prototype") // 原型作用域：每次任务获取新实例，避免单例Bean并发冲突
    public ReActAgent getRouteMakingAgent() {

        // ★高德MCP：只暴露路线综合工具
        // 跨城自驾 query_route_all/query_route/query_distance + 市内游衔接 query_urban_route（步行/公交）
        Toolkit toolkit = new Toolkit();
        AmapEnhancedToolFactory.register(toolkit, AgentType.ROUTE);

        Set<String> toolNames = toolkit.getToolNames(); // 打印挂载的工具
        log.info("=============");
        toolNames.stream().forEach(
                value -> log.info("挂载的工具名称：" + value)
        );
        log.info("=============");

        return AgentUtils.getReActAgentBuilder( // 注入到Nacos
                        "RouteMakingAgent",
                        "擅长处理自驾游路线制定"
                )
                        .memory(new LimitedMemory(Config.getInt("MEMORY_SIZE", 20))) // 限量记忆
                        .toolkit(toolkit) // 工具包
                        // ★规范外部化：sysPrompt 从 resources/agent-rules.md 读取（内容与原硬编码一致）
                        .sysPrompt(loadSysPrompt())
                        .build();
            }

}
