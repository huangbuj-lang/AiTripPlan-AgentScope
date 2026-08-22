package tripPlannerAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import utils.AgentUtils;
import utils.Config;
import utils.LimitedMemory;
import utils.mcp.AmapEnhancedToolFactory;
import utils.mcp.AmapEnhancedToolFactory.AgentType;

/**
 * 行程规划Agent
 */
@Component
public class TripPlannerAgent {

    @Bean
    @Scope("prototype") // ★并发支持：每次A2A任务从容器取新实例（官方标准方案，避免单例状态机并发冲突）
    public ReActAgent getTripPlannerAgent() {

        Toolkit toolkit = new Toolkit();

        // ★高德MCP：只暴露综合工具 query_trip（代码写死固定链，一次调用拿全部行程数据）
        // 模型只调1次即得天气/景点/酒店/美食，不自由ReAct撮合散工具，砍掉多余轮次
        AmapEnhancedToolFactory.register(toolkit, AgentType.TRIP);

        ReActAgent.Builder builder = AgentUtils.getReActAgentBuilder( // 行程规划Agent Builder
                        "TripPlannerAgent",
                        "擅长处理景点行程规划"
                )
                        .memory(new LimitedMemory(Config.getInt("MEMORY_SIZE", 20))) // 限量记忆：单例Agent上下文无限增长会导致状态卡死/大模型400
                        .toolkit(toolkit) // 挂载工具包
                        // ★工具使用铁律：用真实工具查询，严禁凭知识编造；工具失败(⚠️)如实上报
                                                .sysPrompt("你是TripPlannerAgent，一个专业的行程规划Agent。你拥有两个工具：query_trip(高德实时数据：天气/热门景点/住宿酒店/餐饮美食)、query_local_knowledge(本地知识库：已沉淀地点的高分真实数据、类型、档次)。\n"
                                                        + "★调用铁律：规划行程前【必须】先并行调用 query_trip 与 query_local_knowledge 各一次，拿到高德实时数据+本地高分数据，两者融合后规划。\n"
                                                        + "★融合原则：本地知识库与高德重合的地点优先保留；本地高分(如评分≥4.5)地点可优先安排；两者都取不到才可参考通用常识(标明)。\n"
                                                        + "★严禁编造：如果工具返回【⚠️...数据获取失败】或【本地知识获取失败】，你【不得】伪造该数据替代，必须如实写明或缺省该字段，不得编造不存在的景点/价格/评分。\n"
                                + "★独立重试铁律：两个工具各自独立——若 query_trip 返回【⚠️...数据获取失败】或明显空缺，只重新调用 query_trip 一次（带调整后的参数，如换关键词/去掉可疑参数）；若 query_local_knowledge 返回【本地知识获取失败】，只重新调用 query_local_knowledge 一次（如换城市名写法）。谁失败重试谁，禁止因一个工具失败而重复调用另一个正常的工具；重试仍失败则如实标注并继续。\n"
                                                        + "★天数铁律：严格按照任务给定的天数规划，不得自动增减天数。\n"
                                                        + "★最终输出铁律：你的最终回复必须是完整、可直接阅读的行程计划，包含每天具体安排、真实景点名、门票、酒店餐厅名。"
                                + "禁止只输出\"已完成/总结\"等收尾概述；开头直接给内容，禁止\"我已加载/以下为您整理\"等引导语。\n"
                        );

        return builder.build();
    }
}
