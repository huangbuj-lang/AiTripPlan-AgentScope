package tripPlannerAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import utils.AgentUtils;
import utils.Config;
import utils.LimitedMemory;
import utils.mcp.BaiduEnhancedToolFactory;

/**
 * 行程规划Agent
 */
@Component
public class TripPlannerAgent {

    @Bean
    @Scope("prototype") // ★并发支持：每次A2A任务从容器取新实例（官方标准方案，避免单例状态机并发冲突）
    public ReActAgent getTripPlannerAgent() {

        Toolkit toolkit = new Toolkit();

        // ★框架标准MCP + 语义化增强：MCP客户端(10工具) + 人名友好转换工具(6工具)
        BaiduEnhancedToolFactory.register(toolkit);

        /* AgentScope框架自带注册中心AgentScopeA2aServer，
           智能体卡片通过SpringBoot Bean自动注入（或用下方注释代码手动写入） */

        ReActAgent.Builder builder = AgentUtils.getReActAgentBuilder( // 行程规划Agent Builder
                "TripPlannerAgent",
                "擅长处理景点行程规划"
        )
                .memory(new LimitedMemory(Config.getInt("MEMORY_SIZE", 20))) // 限量记忆：单例Agent上下文无限增长会导致状态卡死/大模型400
                .toolkit(toolkit) // 挂载工具包

                ;

        return builder.build();

        //=========== 手动写入注册中心，项目不用种方式 START ====

//        //行程规划Agent 智能体卡片
//        ConfigurableAgentCard agentCard =  new ConfigurableAgentCard.Builder()
//                .name("TripPlannerAgent")
//                .description("行程规划Agent")
//                .build();
//
//        //将智能体卡片写入到AgentScope自带的注册中心
//        AgentScopeA2aServer.builder(builder)
//                .agentCard(agentCard)
//                .deploymentProperties(
//                       new DeploymentProperties(
//                               "localhost",
//                               8080)
//                )
//                .build();

        //还需要AgentScopeA2aServer启动

        //======== 手动写入注册中心，项目不用种方式 END ====

    }
}
