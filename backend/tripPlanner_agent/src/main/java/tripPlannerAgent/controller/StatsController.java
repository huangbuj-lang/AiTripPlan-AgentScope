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
@RestController  // @RestController：返回 JSON
public class StatsController {  // 类声明：StatsController（8085 的观测入口）

    /** 进程启动时间戳（JVM 启动时刻） */
    private static final long START_MS = System.currentTimeMillis()  // START_MS = 当前时间 - JVM 已运行时长
            - ManagementFactory.getRuntimeMXBean().getUptime();  // 续行：getUptime() 毫秒
    /**
     * 作用：观测接口：返回本进程的模型调用次数、token 用量、MCP 调用统计与运行时长
     * 参数：无
     * 返回值：统计 Map
     * 异常：无
     */
    @GetMapping("/stats")  // @GetMapping("/stats")：GET /stats
    public Map<String, Object> stats() {  // stats 签名
        Map<String, Object> m = new HashMap<>();  // 新建结果 Map
        m.put("model_calls", SafeModel.MODEL_CALLS.get()); // 本进程模型调用次数  // model_calls：本进程累计模型调用次数
        m.put("tokens_in", SafeModel.TOKENS_INPUT.get());  // ★成本观测：本进程累计输入tokens  // tokens_in：累计输入 token
        m.put("tokens_out", SafeModel.TOKENS_OUTPUT.get()); // ★成本观测：本进程累计输出tokens  // tokens_out：累计输出 token
        m.put("dim_stats", SafeModel.dimensionStatsSnapshotAsMap()); // ★按维度(目前恒task)的调用/tokens  // dim_stats：按维度的调用与 token（目前恒为 task 维度）
        m.put("startMs", START_MS);                        // 进程启动时间戳  // startMs：进程启动时间戳
        m.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime()); // 已运行时长ms  // uptimeMs：已运行毫秒数
        // ★高德MCP工具调用统计：总数/失败数/按工具名成功率（管理端健康页展示）
        m.put("mcp_calls", AmapRateLimitedMcpClientWrapper.MCP_CALLS.get());  // mcp_calls：MCP 调用总数
        m.put("mcp_fails", AmapRateLimitedMcpClientWrapper.MCP_FAILS.get());  // mcp_fails：失败数
        m.put("mcp_by_name", AmapRateLimitedMcpClientWrapper.mcpNameStatsSnapshot());  // mcp_by_name：按工具名的统计快照
        // ★本地知识库查询统计（行程Agent挂载 query_local_knowledge；路线Agent恒为0）
        m.put("lk_calls", utils.mcp.LocalKnowledgeTool.LK_CALLS.get());  // lk_calls：调用数
        m.put("lk_fails", utils.mcp.LocalKnowledgeTool.LK_FAILS.get());  // lk_fails：失败数
        m.put("lk_cost_ms", utils.mcp.LocalKnowledgeTool.LK_COST_MS.get());  // lk_cost_ms：累计耗时
        // ★综合工具(query_trip/query_route_unified)统计
        m.put("tool_calls", utils.mcp.AmapCompositeTool.TOOL_CALLS.get());  // tool_calls：调用数
        m.put("tool_fails", utils.mcp.AmapCompositeTool.TOOL_FAILS.get());  // tool_fails：失败数
        m.put("tool_cost_ms", utils.mcp.AmapCompositeTool.TOOL_COST_MS.get());  // tool_cost_ms：累计耗时
        return m;  // 返回
    }  // 方法结束
    /**
     * 作用：观测接口：按会话 ID 返回本进程记录到的调用链 span
     * 参数：sessionId 会话 ID
     * 返回值：含 span 列表的 Map
     * 异常：无
     */
    @GetMapping("/trace")  // @GetMapping("/trace")
    public Map<String, Object> trace(@RequestParam("sessionId") String sessionId) {  // trace 签名：sessionId 必填（主管落库时按会话拉）
        Map<String, Object> r = new HashMap<>();  // 新建结果 Map
        r.put("spans", utils.CallTrace.traceAsMaps(sessionId));  // spans：该会话的全部 span（内存 CallTrace）
        r.put("success", true);  // success=true
        return r;  // 返回
    }  // 方法结束

    /**
     * 作用：健康检查接口：返回服务名/端口/Nacos 注册状态/运行时长
     * 参数：无
     * 返回值：健康信息 Map
     * 异常：无
     */
    @GetMapping("/health")  // @GetMapping("/health")
    public Map<String, Object> health() {  // health 签名
        return utils.HealthInfo.of("tripPlanner_agent", 8085, null);  // HealthInfo.of：统一形状（服务名 tripPlanner_agent、端口 8085）
    }  // 方法结束

    /**
     * 作用：观测接口：返回本进程最近一次模型调用失败原因（供主管定位根因）
     * 参数：sessionId 会话 ID，可为空
     * 返回值：含错误原因与时间的 Map
     * 异常：无
     */
    @GetMapping("/lastError")  // @GetMapping("/lastError")
    public Map<String, Object> lastError(@RequestParam(value = "sessionId", required = false) String sessionId) {  // lastError 签名
        Map<String, Object> r = new HashMap<>();  // 新建结果 Map
        r.put("sessionId", sessionId == null ? "" : sessionId);  // 回填 sessionId（null 归一空串）
        String err = SafeModel.lastError(sessionId);  // 取 SafeModel 记录的最后错误
        r.put("error", err == null ? "" : err); // 格式：epochMs|分类原因，如 1757562000000|余额不足(402)  // error 字段：格式 "epochMs|分类原因"，如 1757562000000|余额不足(402)
        r.put("success", true);  // success=true
        return r;  // 返回
    }  // 方法结束

    /**
     * 作用：结果回捞接口：按结果键或时间取最近一次任务结果（A2A 超时后主管会用它对账）
     * 参数：key 结果键，可为空；sinceMs 只接受该时间之后的结果
     * 返回值：含结果文本的 Map
     * 异常：无
     */
    @GetMapping("/lastResult")  // @GetMapping("/lastResult")
    public Map<String, Object> lastResult(  // lastResult 签名第 1 行
            @RequestParam(value = "key", required = false) String key,  // key 参数（可空）
            @RequestParam(value = "sinceMs", required = false) Long sinceMs) {  // sinceMs 参数（可空，Long）
        Map<String, Object> r = new HashMap<>();  // 新建结果 Map
        utils.LastResultStore.Entry e = utils.LastResultStore.take(key);  // 按 key 取结果（take：取出即删，一次性）
        if (e == null && sinceMs != null) {  // 没命中且有 sinceMs
            e = utils.LastResultStore.latestSince(sinceMs);  // 用时间兜底：取该时间之后最近一条结果
        }  // if 结束
        // ★诊断日志：主管回捞是静默的，出问题时无法判断"子Agent 到底存没存、被查的 key 对不对"
        org.slf4j.LoggerFactory.getLogger(StatsController.class)  // 日志：
                .info("[lastResult] 被查 key={} hit={} 当前存档数={}",  // 打印被查 key、是否命中、当前存档条数
                        key, e != null, utils.LastResultStore.stats().get("size"));  // 参数续行
        r.put("success", true);  // success=true
        r.put("hit", e != null);  // hit：是否命中
        r.put("result", e == null ? "" : e.text);  // result：结果文本（没命中为空串）
        r.put("ts", e == null ? 0L : e.ts);  // ts：结果时间戳（没命中为 0）
        return r;  // 返回
    }  // 方法结束

    /**
     * 作用：观测接口：返回结果缓存的条数等统计
     * 参数：无
     * 返回值：统计 Map
     * 异常：无
     */
    @GetMapping("/lastResultStats")  // @GetMapping("/lastResultStats")
    public Map<String, Object> lastResultStats() {  // 方法签名
        Map<String, Object> r = new HashMap<>();  // 新建 Map
        r.put("success", true);  // success=true
        r.putAll(utils.LastResultStore.stats());  // 把 LastResultStore.stats() 全量塞进去（size/命中率等）
        return r;  // 返回
    }  // 方法结束

    /**
     * 作用：观测接口：返回某会话缓存的三域候选块（排查候选数据问题用）
     * 参数：sessionId 会话 ID
     * 返回值：含候选 JSON 的 Map
     * 异常：无
     */
    @GetMapping("/candidate-block")  // @GetMapping("/candidate-block")
    public Map<String, Object> candidateBlock(@RequestParam("sessionId") String sessionId) {  // 方法签名：主管 harvestCandidates 在回复无块时调它
        Map<String, Object> r = new HashMap<>();  // 新建 Map
        r.put("success", true);  // success=true
        r.put("sessionId", sessionId);  // 回填 sessionId
        r.put("block", utils.CandidateBlockStore.get(sessionId));  // block：该会话缓存的候选 JSON
        return r;  // 返回
    }  // 方法结束

    /**
     * 作用：调试接口：直接跑一次三域候选查询并返回结果（不经过 A2A）
     * 参数：destination 目的地；days 天数；preference 偏好；waypoints 途经城市；brief 需求状态块原文；domains 限定域名（空表示全部）；sessionId 会话 ID
     * 返回值：候选数据 Map
     * 异常：无（失败返回含错误信息的 Map）
     */
    @GetMapping("/trip/candidates")  // @GetMapping("/trip/candidates")
    public Map<String, Object> tripCandidates(  // 方法签名开始
            @RequestParam("destination") String destination,  // destination 必填
            @RequestParam(value = "days", required = false, defaultValue = "") String days,  // days（可空）
            @RequestParam(value = "preference", required = false, defaultValue = "") String preference,  // preference（可空）
            @RequestParam(value = "waypoints", required = false, defaultValue = "") String waypoints,  // waypoints（可空）
            @RequestParam(value = "brief", required = false, defaultValue = "") String brief,  // brief（需求状态块原文，可空）
            @RequestParam(value = "domains", required = false, defaultValue = "") String domains,  // domains（限定域名，逗号分隔，空表示全部）
            @RequestParam(value = "sessionId", required = false, defaultValue = "") String sessionId) {  // sessionId（可空）
        Map<String, Object> r = new HashMap<>();  // 新建结果 Map
        try {  // try
            if (sessionId != null && !sessionId.isBlank()) {  // 传了 sessionId
                utils.SafeModel.setSessionId(sessionId); // 域专家 span 归位到本会话  // 把它设进 SafeModel，让域专家的 span 归到这个会话
            }  // if 结束
            java.util.List<String> only = new java.util.ArrayList<>();  // 准备 only 列表（限定要查的域）
            if (domains != null && !domains.isBlank()) {  // domains 非空
                for (String d : domains.split("[,，]")) {  // 按中英文逗号切分
                    String t = d.trim();  // 去空格
                    if (!t.isBlank()) only.add(t);  // 非空才加
                }  // for 结束
            }  // if 结束
            String reply = utils.mcp.DomainCandidateService.run(  // 调 DomainCandidateService.run 真正查候选
                    destination == null ? "" : destination,  // 参数：destination
                    days == null ? "" : days,  // 参数：days
                    preference == null ? "" : preference,  // 参数：preference
                    waypoints == null ? "" : waypoints,  // 参数：waypoints
                    brief == null ? "" : brief,  // 参数：brief
                    only.isEmpty() ? null : only);  // 参数：只有限定域时传列表，否则 null（=全部域）
            r.put("success", !reply.isBlank());  // success：回复非空即成功
            r.put("sessionId", sessionId);  // 回填 sessionId
            r.put("reply", reply); // digest + 【域候选JSON】块（主管 extractBlock 取正文）  // reply：digest + 【域候选JSON】块（调用方用 extractBlock 取正文）
        } catch (Exception e) {  // 异常
            r.put("success", false);  // success=false
            r.put("error", String.valueOf(e.getMessage()));  // error：异常消息
        }  // catch 结束
        return r;  // 返回
    }  // 方法结束

}  // 类结束

