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
@Slf4j  // @Slf4j：注入日志对象
public class CandidatesTool {  // 类声明：三域候选一站式工具（行程门面 Agent 专用）

    private final McpClientWrapper mcp;  // mcp：高德 MCP 客户端
    private final AmapCompositeTool composite;  // composite：高德综合工具（取天气用）
    /**
     * 作用：构造三域候选工具，绑定高德 MCP 客户端
     * 参数：mcp 已初始化的 MCP 客户端
     * 返回值：无
     * 异常：无
     */

    public CandidatesTool(McpClientWrapper mcp) {  // 构造器：绑定 MCP 客户端
        this.mcp = mcp;  // 保存客户端
        this.composite = mcp == null ? null : new AmapCompositeTool(mcp);  // 客户端非空才建综合工具
    }  // 构造器结束

    @Tool(name = "query_trip_candidates",  // @Tool：注册成 Agent 可调用工具
            description = "三域候选一站式工具：一次调用即在服务内部并行调度 景点/酒店/餐饮 三个域专家Agent（各自查真实数据），"  // 工具描述：一次调用内部并行调度三个域专家，
                    + "返回结构化候选 JSON（每域数量随行程天数自动放大，约天数×2.5个/域、封顶18，含天气与置信度）。destination 必填（目的地城市名，如：成都）。")  // 续：返回结构化候选 JSON（数量随天数放大，封顶 18）
    /**
     * 作用：三域候选聚合工具（子 Agent 唯一入口）：一次调用拿景点/酒店/餐饮候选，附营业时间与坐标
     * 参数：destination 目的地城市（必填）；days 行程天数（仅提示）；preference 行程偏好（可选）；waypoints 跨城途经城市（逗号分隔，可选）；brief 【TravelState 统一状态】块原文（原样传入）
     * 返回值：三域候选数据文本
     * 异常：无（失败以文本形式返回）
     */
    public String queryTripCandidates(  // queryTripCandidates：三域候选主方法
            @ToolParam(name = "destination", description = "目的地城市名，必填，如：成都") String destination,  // 参数 destination：目的地城市（必填）
            @ToolParam(name = "days", description = "行程天数（仅提示）") String days,  // 参数 days：行程天数（仅提示）
            @ToolParam(name = "preference", description = "行程偏好，可选") String preference,  // 参数 preference：行程偏好（可选）
            @ToolParam(name = "waypoints", description = "跨城自驾途经城市，逗号分隔，可选") String waypoints,  // 参数 waypoints：跨城途经城市（可选）
            @ToolParam(name = "brief", description = "任务开头的【TravelState 统一状态】块原文，原样传入", required = false) String brief) {  // 参数 brief：【TravelState 统一状态】块原文（漏参时兜底解析用）
        long t0 = System.currentTimeMillis();  // t0：开始时间（算耗时）
        // ★工具边界护栏（PDF §二十二）：LLM 漏填参数时从 brief 权威快照兜底解析，防止空目的地导致三域全降级
        String briefText = brief == null ? "" : brief;  // briefText：brief 归一（null 归空串）
        if (destination == null || destination.isBlank()) {  // destination 没填
            destination = matchBrief(briefText, "目的地");  // 从 brief 里按“目的地:”兜底解析
        }  // if 结束
        if (days == null || days.isBlank()) {  // days 没填
            days = matchBrief(briefText, "天数").replaceAll("[^0-9]", "");  // 从 brief 解析天数并只留数字
        }  // if 结束
        if ((preference == null || preference.isBlank()) && briefText.contains("行程偏好: ")) {  // preference 没填且 brief 里有偏好
            preference = matchBrief(briefText, "行程偏好");  // 从 brief 解析偏好
        }  // if 结束
        log.info("query_trip_candidates: destination={} days={} waypoints={}",  // info 日志：本次调用的三个关键参数
                destination, days, waypoints);  // 续行
        if (destination == null || destination.isBlank()) {  // 兜底后目的地仍为空
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_trip_candidates",  // 记录到调用链：TOOL 层失败
                    "目的地缺失(参数与brief均未提供)", false, System.currentTimeMillis() - t0);  // 续：原因“目的地缺失” + 耗时
            return "⚠️[query_trip_candidates] destination 必填（目的地城市名）。请从任务或【TravelState 统一状态】中提取后重调。";  // 返回提示文本，让模型补齐参数重调
        }  // if 结束
        try {  // try
            String out = DomainCandidateService.run(destination, days, preference, waypoints,  // 交给 DomainCandidateService：内部并行调度三个域专家，
                    briefText);  // 续：返回可读摘要 + 【域候选JSON】块
            // 天气：沿用高德实时预报（域专家不管天气），追加在摘要前供门面安排行程
            String weather = fetchWeather(destination);  // 再查一次天气（域专家不管天气）
            String result = (weather == null || weather.isBlank() ? "" : "【目的地天气】" + weather + "\n\n") + out;  // 把天气拼在结果最前面
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_trip_candidates",  // 记录到调用链：TOOL 层成功
                    "三域候选工具完成 · " + destination  // 续：目的地 + 耗时
                            + " · 耗时" + (System.currentTimeMillis() - t0) + "ms",  // 续行
                    true, System.currentTimeMillis() - t0);  // 续：成败 + 耗时
            return result;  // 返回给 Agent
        } catch (Exception e) {  // 捕获异常
            log.warn("query_trip_candidates 异常: {}", e.getMessage());  // warn 日志
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_trip_candidates",  // 记录到调用链：TOOL 层失败
                    "异常: " + e.getMessage(), false, System.currentTimeMillis() - t0);  // 续：异常信息 + 耗时
            return "⚠️[query_trip_candidates] 三域候选调度失败：" + e.getMessage() + "。可改用 query_trip 兜底。";  // 返回失败提示（建议改用 query_trip 兜底）
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：从候选简要信息中按字段名取值
     * 参数：brief 简要信息文本；field 字段名
     * 返回值：字段值；取不到返回空串
     * 异常：无
     */
    private static String matchBrief(String brief, String field) {  // matchBrief：从状态块里按字段名取值
        if (brief == null || brief.isBlank()) return "";  // 文本为空返回空串
        java.util.regex.Matcher m = java.util.regex.Pattern  // 正则“字段名: 值”
                .compile(field + "\\s*[:：]\\s*(.+)")  // 续行
                .matcher(brief);  // 续行
        if (m.find()) {  // 匹配到
            String v = m.group(1).trim();  // 取捕获值并 trim
            int cut = v.indexOf('\n');  // 找换行位置
            return cut >= 0 ? v.substring(0, cut).trim() : v;  // 截到行尾（只取本字段，不吞下一行）
        }  // if 结束
        return "";  // 没匹配返回空串
    }  // 方法结束

    /**
     * 作用：查城市天气（候选数据里附带天气）
     * 参数：city 城市名
     * 返回值：天气文本；失败返回空串
     * 异常：无
     */
    private String fetchWeather(String city) {  // fetchWeather：查城市天气
        if (composite == null || city == null || city.isBlank()) return null;  // 工具/城市无效返回 null
        try {  // try
            String r = composite.queryWeather(city.trim());  // 走高德综合工具查天气
            if (r == null || r.contains("⚠️")) return null;  // 失败（含 ⚠️ 标记）返回 null
            return r.length() > 400 ? r.substring(0, 400) : r;  // 截到 400 字（防提示词膨胀）
        } catch (Exception e) {  // 捕获异常
            return null;  // 失败返回 null
        }  // catch 结束
    }  // 方法结束
}  // 类结束
