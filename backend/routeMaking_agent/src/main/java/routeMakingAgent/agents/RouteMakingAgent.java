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
import utils.mcp.BaiduEnhancedToolFactory;

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

        // ★框架标准MCP + 语义化增强：MCP客户端(10工具) + 人名友好转换工具(6工具)
        Toolkit toolkit = new Toolkit();
        BaiduEnhancedToolFactory.register(toolkit);

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
                .memory(new LimitedMemory(Config.getInt("MEMORY_SIZE", 20))) // 限量记忆
                .toolkit(toolkit) // 工具包
                .build();
    }

}
