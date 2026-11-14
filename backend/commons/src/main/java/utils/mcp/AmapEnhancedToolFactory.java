package utils.mcp;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 高德工具装配工厂：综合工具 + 按 Agent 裁剪工具集。
 * 注册高德MCP客户端(拿数据) + 注册复合工具(封依赖链)，
 * 隐藏要求坐标的原版 maps_* 工具，按 Agent 类型裁剪暴露面，
 * 让子 Agent 只能调用"代码写死的固定链综合工具"，而不是自由 ReAct 调一堆散工具。
 */
@Slf4j  // @Slf4j：注入日志对象
public class AmapEnhancedToolFactory {  // 类声明：高德工具装配工厂（复合工具 + 按 Agent 裁剪）

    /** Agent 类型：决定暴露哪组复合工具 */
    public enum AgentType {  // AgentType 枚举：决定暴露哪组工具
        /** 行程Agent（门面）：暴露 query_candidates（并行三域候选）+ query_trip（兜底） */
        TRIP,  // TRIP：行程门面 Agent
        /** 路线Agent：只暴露路线综合工具（驾车路线+距离） */
        ROUTE,  // ROUTE：路线 Agent
        /** 景点域专家Agent：地点搜索+详情 */
        ATTRACTION,  // ATTRACTION：景点域专家
        /** 酒店域专家Agent：地点搜索 */
        HOTEL,  // HOTEL：酒店域专家
        /** 餐饮域专家Agent：地点搜索 */
        DINING  // DINING：餐饮域专家
    }  // 枚举结束

    /** Wait once during JVM startup so the first toolkit uses the latest Nacos snapshot. */
    private static final AtomicBoolean CONFIG_WAITED = new AtomicBoolean();  // CONFIG_WAITED：启动期只等一次 Nacos 配置

    /** MCP客户端（单例复用，避免每次重建连接） */
    private static volatile McpClientWrapper MCP;  // MCP：高德 MCP 客户端单例

    /** 要隐藏的高德原版工具名列表（模型不该直接调，坐标/底层接口）。高德MCP共15个 maps_* 工具，全部隐藏。 */
    private static final String[] HIDE_TOOLS = {  // HIDE_TOOLS：要隐藏的高德原版工具（模型不该直接调）
            "maps_geo", "maps_regeocode", "maps_weather", "maps_text_search",  // 原版地理编码（要坐标，模型容易填错）
            "maps_around_search", "maps_search_detail",  // 原版周边搜索/详情
            "maps_direction_driving", "maps_direction_walking", "maps_direction_bicycling",  // 原版三种路线规划（应走复合工具）
            "maps_direction_transit_integrated", "maps_distance",  // 原版公交/距离（应走复合工具）
            "maps_ip_location", "maps_schema_navi", "maps_schema_take_taxi", "maps_schema_personal_map"  // 原版 IP 定位/导航/打车/个人地图
    };  // 数组结束

    /** 全部复合工具名（来自 AmapCompositeTool 的 @Tool） */
    private static final Set<String> ALL_COMPOSITE = Set.of(  // 复合工具清单（
            "query_trip", "query_trip_candidates", "query_route_all", "query_route", "query_around",  // 续：来自 AmapCompositeTool 与 CandidatesTool 的 @Tool）
            "query_distance", "query_weather", "query_places", "query_place_detail",  // 续行
            "query_urban_route", "query_route_unified");  // 续行

    /** 行程Agent（门面）保留的复合工具：query_candidates 主力 + query_trip 兜底 */
    private static final Set<String> TRIP_KEEP = Set.of("query_trip", "query_trip_candidates");  // TRIP_KEEP：行程门面保留 query_trip（兜底）+ query_trip_candidates（主力）
    /** 路线Agent保留的唯一综合工具：query_route_unified（内部按mode区分自驾/市内步行公交） */
    private static final Set<String> ROUTE_KEEP = Set.of("query_route_unified");  // ROUTE_KEEP：路线 Agent 只保留 query_route_unified
    /** 景点域专家保留：地点搜索 + 详情（评分/门票/开放时间） */
    private static final Set<String> ATTRACTION_KEEP = Set.of("query_places", "query_place_detail");  // ATTRACTION_KEEP：景点专家保留搜索 + 详情
    /** 酒店/餐饮域专家保留：地点搜索 */
    private static final Set<String> HOTEL_KEEP = Set.of("query_places");  // HOTEL_KEEP：酒店专家只保留搜索
    private static final Set<String> DINING_KEEP = Set.of("query_places");  // DINING_KEEP：餐饮专家只保留搜索

    /**
     * 作用：注册全部高德工具（不含子 Agent 专属清单）
     * 参数：toolkit 目标工具包
     * 返回值：无
     * 异常：无
     */
    public static void register(Toolkit toolkit) {  // register（单参）：默认按行程 Agent 装配
        register(toolkit, AgentType.TRIP);  // 委托两参版本
    }  // 方法结束

    /**
     * 作用：按 Agent 类型注册工具：行程 Agent 只挂 query_trip 聚合工具，路线 Agent 只挂 query_route_unified，并把描述改为可热更版本
     * 参数：toolkit 目标工具包；type Agent 类型（TRIP/ROUTE）
     * 返回值：无
     * 异常：无
     */
    public static void register(Toolkit toolkit, AgentType type) {  // register：按 Agent 类型装配工具
        if (CONFIG_WAITED.compareAndSet(false, true) && !utils.AgentRuntimeConfig.loaded()) {  // 启动期只等一次 Nacos 配置加载
            if (!utils.AgentRuntimeConfig.awaitLoaded(5_000L)) {  // 最多等 5 秒
                log.warn("Nacos agent runtime config not loaded within 5s; using environment fallback");  // 超时只 warn，用环境变量兜底
            }  // if 结束
        }  // if 结束
        boolean amapEnabled = utils.AgentRuntimeConfig.toolEnabled("amap", true);  // amapEnabled：高德工具开关
        boolean localKnowledgeEnabled = utils.AgentRuntimeConfig.toolEnabled("localKnowledge", true);  // localKnowledgeEnabled：本地知识库开关
        if (!amapEnabled && (type == AgentType.ROUTE || !localKnowledgeEnabled)) {  // 高德关了且（路线 Agent 或本地库也关）
            log.warn("Agent tools disabled by Nacos config: agentType={}, amap={}, localKnowledge={}",  // warn：该 Agent 无可用工具
                    type, amapEnabled, localKnowledgeEnabled);  // 续行
            return;  // 直接返回
        }  // if 结束

        if (amapEnabled) {  // 高德工具启用
            McpClientWrapper mcp = getMcp();  // 取（或创建）MCP 客户端单例
            toolkit.registerMcpClient(mcp).block();                      // 高德MCP客户端(数据源)  // 注册 MCP 客户端（阻塞等工具列表拉取）
            // 隐藏要求坐标的原版工具
            for (String t : HIDE_TOOLS) {  // 逐个隐藏原版工具
                try { toolkit.removeTool(t); } catch (Exception ignored) { }  // 移除失败忽略（工具名可能不存在）
            }  // for 结束
            // 注册全部复合工具
            toolkit.registration().tool(new AmapCompositeTool(mcp)).apply();  // 注册高德综合工具（复合链路）
            toolkit.registration().tool(new CandidatesTool(mcp)).apply();  // 注册三域候选工具
            Set<String> keep = switch (type) {  // keep：本 Agent 要保留的复合工具
                case ROUTE -> ROUTE_KEEP;  // ROUTE → 只留统一路线工具
                case ATTRACTION -> ATTRACTION_KEEP;  // ATTRACTION → 搜索 + 详情
                case HOTEL -> HOTEL_KEEP;  // HOTEL → 搜索
                case DINING -> DINING_KEEP;  // DINING → 搜索
                default -> TRIP_KEEP;  // TRIP（默认）→ query_trip + 三域候选
            };  // switch 结束
            for (String t : ALL_COMPOSITE) {  // 遍历全部复合工具
                if (!keep.contains(t)) {  // 不在保留清单里
                    try { toolkit.removeTool(t); } catch (Exception ignored) { }  // 移除
                }  // if 结束
            }  // for 结束
            HotToolDescription.wrap(toolkit, "query_trip", "toolQueryTrip",  // 把 query_trip 换成描述可热更版本
                    utils.AgentPrompts.toolQueryTrip());  // 续：描述取自提示词库 toolQueryTrip
            HotToolDescription.wrap(toolkit, "query_route_unified", "toolRouteUnified",  // 把 query_route_unified 换成可热更版本
                    utils.AgentPrompts.toolRouteUnified());  // 续行
            if (type == AgentType.TRIP) {  // 行程门面额外处理三域候选工具
                HotToolDescription.wrap(toolkit, "query_trip_candidates", "toolQueryCandidates",  // 把 query_trip_candidates 换成可热更版本
                        utils.AgentPrompts.toolQueryCandidates());  // 续行
            }  // if 结束
        }  // if 结束

        // 行程门面与三域专家挂本地知识库工具；路线Agent不挂
        boolean domainAgent = type == AgentType.TRIP || type == AgentType.ATTRACTION  // domainAgent：行程门面与三个域专家
                || type == AgentType.HOTEL || type == AgentType.DINING;  // 续行
        if (domainAgent && localKnowledgeEnabled) {  // 是域类 Agent 且本地知识库启用
            try {  // try
                toolkit.registration().tool(new LocalKnowledgeTool()).apply();  // 注册本地知识库工具
            } catch (Exception e) {  // 捕获异常
                log.warn("Register LocalKnowledgeTool failed: {}", e.getMessage());  // warn：注册失败（不影响其他工具）
            }  // catch 结束
            HotToolDescription.wrap(toolkit, "query_local_knowledge", "toolLocalKnowledge",  // 把 query_local_knowledge 换成可热更版本
                    utils.AgentPrompts.toolLocalKnowledge());  // 续行
        }  // if 结束
    }  // 方法结束
    /**
     * 作用：获取并初始化高德 MCP 客户端（进程内单例，初步初始化失败会重试）
     * 参数：无
     * 返回值：已初始化的 MCP 客户端
     * 异常：无（初始化失败返回 null，由调用方降级）
     */

    private static McpClientWrapper getMcp() {  // getMcp：取并初始化 MCP 客户端（双重检查锁单例）
        if (MCP == null) {  // 还没创建
            synchronized (AmapEnhancedToolFactory.class) {  // 类级锁
                if (MCP == null) {  // 锁内再判
                    MCP = AmapMcpClientFactory.createInitialized();  // 创建并初始化（失败抛运行时异常）
                }  // if 结束
            }  // synchronized 结束
        }  // if 结束
        return MCP;  // 返回单例
    }  // 方法结束
}  // 类结束
