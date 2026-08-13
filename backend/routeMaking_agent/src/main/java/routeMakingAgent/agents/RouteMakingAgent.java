package routeMakingAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
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
    @Scope("prototype") // 原型作用域：每次任务获取新实例，避免单例Bean并发冲突
    public ReActAgent getRouteMakingAgent() {

        // 使用普通@Tool工具替代MCP客户端注册，直接调用百度地图MCP
        BaiduMapTool baiduMapTool = new BaiduMapTool();

        ToolUtils toolUtils = new ToolUtils(false); // 工具包（普通工具注册，串行执行）
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
                .memory(new LimitedMemory(20)) // 限量记忆
                .toolkit(toolkit) // 工具包
                .build();
    }

}
