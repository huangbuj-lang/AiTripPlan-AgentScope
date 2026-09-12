package tripPlannerAgent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utils.SafeModel;
import utils.mcp.AmapRateLimitedMcpClientWrapper;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

/** 观测接口：返回本进程的运行状态（管理端子Agent健康度用） */
@RestController
public class StatsController {

    /** 进程启动时间戳（JVM 启动时刻） */
    private static final long START_MS = System.currentTimeMillis()
            - ManagementFactory.getRuntimeMXBean().getUptime();

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new HashMap<>();
        m.put("model_calls", SafeModel.MODEL_CALLS.get()); // 本进程模型调用次数
        m.put("tokens_in", SafeModel.TOKENS_INPUT.get());  // 成本观测：本进程累计输入tokens
        m.put("tokens_out", SafeModel.TOKENS_OUTPUT.get()); // 成本观测：本进程累计输出tokens
        m.put("dim_stats", SafeModel.dimensionStatsSnapshotAsMap()); // 按维度(目前恒task)的调用/tokens
        m.put("startMs", START_MS);                        // 进程启动时间戳
        m.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime()); // 已运行时长ms
        //高德MCP工具调用统计：总数/失败数/按工具名成功率（管理端健康页展示）
        m.put("mcp_calls", AmapRateLimitedMcpClientWrapper.MCP_CALLS.get());
        m.put("mcp_fails", AmapRateLimitedMcpClientWrapper.MCP_FAILS.get());
        m.put("mcp_by_name", AmapRateLimitedMcpClientWrapper.mcpNameStatsSnapshot());
        //本地知识库查询统计
        m.put("lk_calls", utils.mcp.LocalKnowledgeTool.LK_CALLS.get());
        m.put("lk_fails", utils.mcp.LocalKnowledgeTool.LK_FAILS.get());
        m.put("lk_cost_ms", utils.mcp.LocalKnowledgeTool.LK_COST_MS.get());
        //综合工具(query_trip/query_route_unified)统计
        m.put("tool_calls", utils.mcp.AmapCompositeTool.TOOL_CALLS.get());
        m.put("tool_fails", utils.mcp.AmapCompositeTool.TOOL_FAILS.get());
        m.put("tool_cost_ms", utils.mcp.AmapCompositeTool.TOOL_COST_MS.get());
        return m;
    }
    /** 供管理端合并任务调用链：返回指定 sessionId 的 span（TOOL/MCP 层） */
    @GetMapping("/trace")
    public Map<String, Object> trace(@RequestParam("sessionId") String sessionId) {
        Map<String, Object> r = new HashMap<>();
        r.put("spans", utils.CallTrace.traceAsMaps(sessionId));
        r.put("success", true);
        return r;
    }

}
