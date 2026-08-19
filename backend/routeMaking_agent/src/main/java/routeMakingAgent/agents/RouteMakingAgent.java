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
import utils.mcp.BaiduMapTool;

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

        // 百度地图MCP工具（真实数据源）
        BaiduMapTool baiduMapTool = new BaiduMapTool();

        // ★挂载方式必须用 registration().tool()：实测 ToolUtils.getToolkit() 触发
        // AgentScope 1.0.8 的 ToolUseBlock 被吞 bug（模型调工具→被丢弃→重试→轮次爆炸→编造失败）
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(baiduMapTool).apply();

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
