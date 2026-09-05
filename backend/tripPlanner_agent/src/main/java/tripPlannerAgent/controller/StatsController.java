package tripPlannerAgent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utils.SafeModel;
import utils.mcp.AmapRateLimitedMcpClientWrapper;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * 观测接口：返回本进程的运行状态（管理端子Agent健康度用）
 */
@RestController
public class StatsController {

    /** 进程启动时间戳（JVM 启动时刻） */
    private static final long START_MS = System.currentTimeMillis()
            - ManagementFactory.getRuntimeMXBean().getUptime();

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new HashMap<>();
        m.put("model_calls", SafeModel.MODEL_CALLS.get()); // 本进程模型调用次数
        m.put("tokens_in", SafeModel.TOKENS_INPUT.get());  // ★成本观测：本进程累计输入tokens
        m.put("tokens_out", SafeModel.TOKENS_OUTPUT.get()); // ★成本观测：本进程累计输出tokens
        m.put("dim_stats", SafeModel.dimensionStatsSnapshotAsMap()); // ★按维度(目前恒task)的调用/tokens
        m.put("startMs", START_MS);                        // 进程启动时间戳
        m.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime()); // 已运行时长ms
        // ★高德MCP工具调用统计：总数/失败数/按工具名成功率（管理端健康页展示）
        m.put("mcp_calls", AmapRateLimitedMcpClientWrapper.MCP_CALLS.get());
        m.put("mcp_fails", AmapRateLimitedMcpClientWrapper.MCP_FAILS.get());
        m.put("mcp_by_name", AmapRateLimitedMcpClientWrapper.mcpNameStatsSnapshot());
        // ★本地知识库查询统计（行程Agent挂载 query_local_knowledge；路线Agent恒为0）
        m.put("lk_calls", utils.mcp.LocalKnowledgeTool.LK_CALLS.get());
        m.put("lk_fails", utils.mcp.LocalKnowledgeTool.LK_FAILS.get());
        m.put("lk_cost_ms", utils.mcp.LocalKnowledgeTool.LK_COST_MS.get());
        // ★综合工具(query_trip/query_route_unified)统计
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

    /** ★观测代码化：主管收割回取候选块——本进程产出的候选 JSON 由代码存档（DomainCandidateService.put），
     *  不依赖门面 LLM 把工具返回的【域候选JSON】块抄进最终回复；主管在回复里提取不到时直连本端点取。 */
    @GetMapping("/candidate-block")
    public Map<String, Object> candidateBlock(@RequestParam("sessionId") String sessionId) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("sessionId", sessionId);
        r.put("block", utils.CandidateBlockStore.get(sessionId));
        return r;
    }

    /** ★删聚合·候选前置查询（主管直连触发）：在本进程内并行调度三域专家查候选（域间3线程），
     *  返回 digest + 【域候选JSON】块文本（主管解析后注入行程Agent任务，供其带全量信息一次定稿）。
     *  与行程Agent ReAct 内调 query_trip_candidates 完全等价——同一 DomainCandidateService，只是触发方从门面LLM变为主管代码。 */
    @GetMapping("/trip/candidates")
    public Map<String, Object> tripCandidates(
            @RequestParam("destination") String destination,
            @RequestParam(value = "days", required = false, defaultValue = "") String days,
            @RequestParam(value = "preference", required = false, defaultValue = "") String preference,
            @RequestParam(value = "waypoints", required = false, defaultValue = "") String waypoints,
            @RequestParam(value = "brief", required = false, defaultValue = "") String brief,
            @RequestParam(value = "sessionId", required = false, defaultValue = "") String sessionId) {
        Map<String, Object> r = new HashMap<>();
        try {
            if (sessionId != null && !sessionId.isBlank()) {
                utils.SafeModel.setSessionId(sessionId); // 域专家 span 归位到本会话
            }
            String reply = utils.mcp.DomainCandidateService.run(
                    destination == null ? "" : destination,
                    days == null ? "" : days,
                    preference == null ? "" : preference,
                    waypoints == null ? "" : waypoints,
                    brief == null ? "" : brief);
            r.put("success", !reply.isBlank());
            r.put("sessionId", sessionId);
            r.put("reply", reply); // digest + 【域候选JSON】块（主管 extractBlock 取正文）
        } catch (Exception e) {
            r.put("success", false);
            r.put("error", String.valueOf(e.getMessage()));
        }
        return r;
    }

}
