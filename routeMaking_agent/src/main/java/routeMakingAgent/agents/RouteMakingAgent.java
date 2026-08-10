package routeMakingAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import routeMakingAgent.mcp.BaiduMapTool;
import utils.AgentUtils;
import utils.LimitedMemory;
import utils.ToolUtils;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 路线制定Agent
 */
@Component
@Slf4j
public class RouteMakingAgent {

    @Bean
    public ReActAgent getRouteMakingAgent() {

        // 用普通@Tool工具替代MCP客户端注册
        // （AgentScope 1.0.8的registerMcpClient在魔塔MCP v1.9.4下有转换bug→首轮请求400超长；
        BaiduMapTool baiduMapTool = new BaiduMapTool(); // BaiduMapTool内部直接调魔塔MCP的tools/call，绕开转换层）

        ToolUtils toolUtils = new ToolUtils(); // 工具包（普通工具注册路径，AgentScope对@Tool转换正常）
        Toolkit toolkit = toolUtils.getToolkit(baiduMapTool);

        Set<String> toolNames = toolkit.getToolNames(); // 打印挂载的工具
        log.info("=============");
        toolNames.stream().forEach(
                value -> log.info("挂载的工具名称："+value)
        );
        log.info("=============");

        return AgentUtils.getReActAgentBuilder( // 注入到Nacos
                "RouteMakingAgent",
                "擅长处理自驾游路线制定"
        )
                .memory(new LimitedMemory(20)) // 限量记忆：单例Agent上下文无限增长会导致大模型400超长报错
                .toolkit(toolkit) // 工具包
                .build();
    }

}
