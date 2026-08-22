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
                        // ★工具使用铁律：用真实工具查询，严禁凭知识编造；工具失败(⚠️)如实上报
                        .sysPrompt("你是RouteMakingAgent，一个专业的路线规划Agent。你只拥有一个综合工具 query_route_unified，它一次调用即可返回两点间路线：跨城自驾(mode=driving)或市内衔接(mode=urban，短距步行/长距公交地铁)。\n"
                                + "★固定链铁律：任务说跨城自驾就用 mode=driving；任务说市内/周边游（没给出发地）就用 mode=urban。一次工具调用获取一段路线，不要反复调用/试多种方式。\n"
                                + "★严禁编造：工具返回【⚠️...数据获取失败】时，不得伪造该数据，必须如实写明\"该数据获取失败\"。\n"
                                + "★独立重试铁律：若 query_route_unified 返回【⚠️...数据获取失败】或明显空缺/无路线数据，调整参数后重新调用一次（如简化地名、换 mode）；重试仍失败则如实标注并继续。\n"
                                + "★天数铁律：严格按照任务给定的天数规划，不得增加天数、不得扩展成更长环线；天数只允许少不允许多。\n"
                                + "★市内游：任务标注为'市内/周边游'时，专注景点间步行/公交衔接，不要硬塞无关跨城路段。\n"
                                + "★最终输出铁律：最终回复必须是完整可直接阅读的行程计划，含每日安排、真实途经点、里程/耗时、衔接方式。禁止\"已完成/总结\"概述，开头直接给内容。"
                        )
                        .build();
            }

}
