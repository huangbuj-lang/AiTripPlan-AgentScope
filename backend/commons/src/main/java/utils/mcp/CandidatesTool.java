package utils.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 三域候选一站式工具（行程门面 Agent 专用）：
 * 一次调用 → DomainCandidateService 在本服务内并行调度 景点/酒店/餐饮 三个域专家 Agent
 * → 返回可读摘要 + 【域候选JSON】块（门面 Agent 原样透传给主管）。
 */
@Slf4j
public class CandidatesTool {

    private final McpClientWrapper mcp;
    private final AmapCompositeTool composite;

    public CandidatesTool(McpClientWrapper mcp) {
        this.mcp = mcp;
        this.composite = mcp == null ? null : new AmapCompositeTool(mcp);
    }

    @Tool(name = "query_trip_candidates",
            description = "三域候选一站式工具：一次调用即在服务内部并行调度 景点/酒店/餐饮 三个域专家Agent（各自查真实数据），"
                    + "返回结构化 Top-3 候选JSON（含天气与置信度）。destination 必填（目的地城市名，如：成都）。")
    public String queryTripCandidates(
            @ToolParam(name = "destination", description = "目的地城市名，必填，如：成都") String destination,
            @ToolParam(name = "days", description = "行程天数（仅提示）") String days,
            @ToolParam(name = "preference", description = "行程偏好，可选") String preference,
            @ToolParam(name = "waypoints", description = "跨城自驾途经城市，逗号分隔，可选") String waypoints,
            @ToolParam(name = "brief", description = "任务开头的【TravelState 统一状态】块原文，原样传入", required = false) String brief) {
        long t0 = System.currentTimeMillis();
        // ★工具边界护栏（PDF §二十二）：LLM 漏填参数时从 brief 权威快照兜底解析，防止空目的地导致三域全降级
        String briefText = brief == null ? "" : brief;
        if (destination == null || destination.isBlank()) {
            destination = matchBrief(briefText, "目的地");
        }
        if (days == null || days.isBlank()) {
            days = matchBrief(briefText, "天数").replaceAll("[^0-9]", "");
        }
        if ((preference == null || preference.isBlank()) && briefText.contains("行程偏好: ")) {
            preference = matchBrief(briefText, "行程偏好");
        }
        log.info("query_trip_candidates: destination={} days={} waypoints={}",
                destination, days, waypoints);
        if (destination == null || destination.isBlank()) {
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_trip_candidates",
                    "目的地缺失(参数与brief均未提供)", false, System.currentTimeMillis() - t0);
            return "⚠️[query_trip_candidates] destination 必填（目的地城市名）。请从任务或【TravelState 统一状态】中提取后重调。";
        }
        try {
            String out = DomainCandidateService.run(destination, days, preference, waypoints,
                    briefText);
            // 天气：沿用高德实时预报（域专家不管天气），追加在摘要前供门面安排行程
            String weather = fetchWeather(destination);
            String result = (weather == null || weather.isBlank() ? "" : "【目的地天气】" + weather + "\n\n") + out;
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_trip_candidates",
                    "三域候选工具完成 · " + destination
                            + " · 耗时" + (System.currentTimeMillis() - t0) + "ms",
                    true, System.currentTimeMillis() - t0);
            return result;
        } catch (Exception e) {
            log.warn("query_trip_candidates 异常: {}", e.getMessage());
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_trip_candidates",
                    "异常: " + e.getMessage(), false, System.currentTimeMillis() - t0);
            return "⚠️[query_trip_candidates] 三域候选调度失败：" + e.getMessage() + "。可改用 query_trip 兜底。";
        }
    }

    /** 从 brief 权威快照提取 "字段名: 值" 行的值（行尾截止） */
    private static String matchBrief(String brief, String field) {
        if (brief == null || brief.isBlank()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(field + "\\s*[:：]\\s*(.+)")
                .matcher(brief);
        if (m.find()) {
            String v = m.group(1).trim();
            int cut = v.indexOf('\n');
            return cut >= 0 ? v.substring(0, cut).trim() : v;
        }
        return "";
    }

    /** 高德实时天气（复用复合工具的 maps_weather 链路；失败返回 null，不阻断候选主链路） */
    private String fetchWeather(String city) {
        if (composite == null || city == null || city.isBlank()) return null;
        try {
            String r = composite.queryWeather(city.trim());
            if (r == null || r.contains("⚠️")) return null;
            return r.length() > 400 ? r.substring(0, 400) : r;
        } catch (Exception e) {
            return null;
        }
    }
}
