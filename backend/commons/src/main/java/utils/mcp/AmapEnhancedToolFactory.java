package utils.mcp;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 高德工具装配工厂：综合工具 + 按 Agent 裁剪工具集。 */
@Slf4j
public class AmapEnhancedToolFactory {

    /** Agent 类型：决定暴露哪组复合工具 */
    public enum AgentType {
        /** 行程Agent：只暴露 query_trip（一次拿齐天气/景点/酒店/美食） */
        TRIP,
        /** 路线Agent：只暴露路线综合工具（驾车路线+距离） */
        ROUTE
    }

    /** Wait once during JVM startup so the first toolkit uses the latest Nacos snapshot. */
    private static final AtomicBoolean CONFIG_WAITED = new AtomicBoolean();

    /** MCP客户端（单例复用，避免每次重建连接） */
    private static volatile McpClientWrapper MCP;

        /** 要隐藏的高德原版工具名列表（模型不该直接调，坐标/底层接口）。 */
    private static final String[] HIDE_TOOLS = {
            "maps_geo", "maps_regeocode", "maps_weather", "maps_text_search",
            "maps_around_search", "maps_search_detail",
            "maps_direction_driving", "maps_direction_walking", "maps_direction_bicycling",
            "maps_direction_transit_integrated", "maps_distance",
            "maps_ip_location", "maps_schema_navi", "maps_schema_take_taxi", "maps_schema_personal_map"
    };

    /** 全部复合工具名（来自 AmapCompositeTool 的 @Tool） */
    private static final Set<String> ALL_COMPOSITE = Set.of(
            "query_trip", "query_route_all", "query_route", "query_around",
            "query_distance", "query_weather", "query_places", "query_place_detail",
            "query_urban_route", "query_route_unified");

    /** 行程Agent保留的复合工具（固定链综合工具，模型只调它拿全部行程数据） */
    private static final Set<String> TRIP_KEEP = Set.of("query_trip");
    /** 路线Agent保留的唯一综合工具：query_route_unified（内部按mode区分自驾/市内步行公交） */
    private static final Set<String> ROUTE_KEEP = Set.of("query_route_unified");

    /** 默认装配：行程Agent（兼容旧调用） */
    public static void register(Toolkit toolkit) {
        register(toolkit, AgentType.TRIP);
    }

        /** 注册到给定Toolkit：MCP客户端(数据源) + 复合工具(封装依赖链)，并按Agent类型裁剪暴露面。 */
    public static void register(Toolkit toolkit, AgentType type) {
        if (CONFIG_WAITED.compareAndSet(false, true) && !utils.AgentRuntimeConfig.loaded()) {
            if (!utils.AgentRuntimeConfig.awaitLoaded(5_000L)) {
                log.warn("Nacos agent runtime config not loaded within 5s; using environment fallback");
            }
        }
        boolean amapEnabled = utils.AgentRuntimeConfig.toolEnabled("amap", true);
        boolean localKnowledgeEnabled = utils.AgentRuntimeConfig.toolEnabled("localKnowledge", true);
        if (!amapEnabled && (type == AgentType.ROUTE || !localKnowledgeEnabled)) {
            log.warn("Agent tools disabled by Nacos config: agentType={}, amap={}, localKnowledge={}",
                    type, amapEnabled, localKnowledgeEnabled);
            return;
        }

        if (amapEnabled) {
            McpClientWrapper mcp = getMcp();
            toolkit.registerMcpClient(mcp).block();                      // 高德MCP客户端(数据源)
            //隐藏要求坐标的原版工具
            for (String t : HIDE_TOOLS) {
                try { toolkit.removeTool(t); } catch (Exception ignored) { }
            }
            //注册全部复合工具
            toolkit.registration().tool(new AmapCompositeTool(mcp)).apply();
            Set<String> keep = type == AgentType.ROUTE ? ROUTE_KEEP : TRIP_KEEP;
            for (String t : ALL_COMPOSITE) {
                if (!keep.contains(t)) {
                    try { toolkit.removeTool(t); } catch (Exception ignored) { }
                }
            }
            HotToolDescription.wrap(toolkit, "query_trip", "toolQueryTrip",
                    utils.AgentPrompts.toolQueryTrip());
            HotToolDescription.wrap(toolkit, "query_route_unified", "toolRouteUnified",
                    utils.AgentPrompts.toolRouteUnified());
        }

        //行程Agent额外挂本地知识库工具
        if (type == AgentType.TRIP && localKnowledgeEnabled) {
            try {
                toolkit.registration().tool(new LocalKnowledgeTool()).apply();
            } catch (Exception e) {
                log.warn("Register LocalKnowledgeTool failed: {}", e.getMessage());
            }
            HotToolDescription.wrap(toolkit, "query_local_knowledge", "toolLocalKnowledge",
                    utils.AgentPrompts.toolLocalKnowledge());
        }
    }

    private static McpClientWrapper getMcp() {
        if (MCP == null) {
            synchronized (AmapEnhancedToolFactory.class) {
                if (MCP == null) {
                    MCP = AmapMcpClientFactory.createInitialized();
                }
            }
        }
        return MCP;
    }
}
