package managerAgent.controller;

import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import managerAgent.hook.EventStore;
import managerAgent.tool.RemoteTimeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import utils.AgentPrompts;
import utils.AgentPromptStore;
import utils.AgentRuntimeConfig;
import utils.CircuitBreaker;
import utils.Config;
import utils.ExternalServices;
import utils.NacosUtil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理端接口（均需 admin 角色，由 AdminInterceptor 校验）。
 * 提供系统状态 / 任务监控 / 模型监控 / 数据管理四个维度的可观测与运营能力。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Resource
    private JdbcTemplate jdbcTemplate;
    private final AtomicBoolean agentRestarting = new AtomicBoolean();

    // ==================== 系统状态 ====================

    /** 各 Agent 健康/注册状态、端口、Nacos 连接情况 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> r = new HashMap<>();

        Map<String, Object> manager = new HashMap<>();
        manager.put("name", "Orchestrator"); // 主管（确定性编排器 TripOrchestrator）
        manager.put("label", "主管");
        manager.put("status", "running");
        manager.put("port", 9091);
        r.put("manager", manager);

        boolean nacosOk = true;
        String nacosErr = null;
        try {
            NacosUtil.getNacosClient();
        } catch (Exception e) {
            nacosOk = false;
            nacosErr = e.getMessage();
        }
        Map<String, Object> nacos = new HashMap<>();
        nacos.put("addr", "localhost:8848");
        nacos.put("connected", nacosOk);
        nacos.put("error", nacosErr);
        r.put("nacos", nacos);

        List<Map<String, Object>> agents = new ArrayList<>();
        agents.add(agentStatus("RouteMakingAgent", 8082, "路线"));
        agents.add(agentStatus("TripPlannerAgent", 8085, "行程"));
        r.put("agents", agents);

        r.put("success", true);
        return r;
    }

    /** 单个远程 Agent 的注册状态（查 Nacos 名片是否存在） */
    private Map<String, Object> agentStatus(String name, int port, String label) {
        Map<String, Object> a = new HashMap<>();
        a.put("name", name);
        a.put("label", label); // 中文标签：路线/行程
        a.put("port", port);
        boolean registered = false;
        try {
            NacosAgentCardResolver resolver = new NacosAgentCardResolver(NacosUtil.getNacosClient());
            registered = resolver.getAgentCard(name) != null;
        } catch (Exception e) {
            registered = false;
        }
        a.put("registered", registered);
        a.put("status", registered ? "已注册" : "离线");
        return a;
    }

    // ==================== 任务监控 ====================

    /** 并发槽位占用 + 全部会话概览 */
    @GetMapping("/tasks")
    public Map<String, Object> tasks() {
        Map<String, Object> r = new HashMap<>();
        r.put("maxConcurrent", AppController.maxConcurrent());
        r.put("inUse", AppController.inUseSlots());
        r.put("sessions", EventStore.listSessions());
        // 任务监控扩展：结果计数（本次进程运行以来）
        r.put("countDone", EventStore.COUNT_DONE.get());
        r.put("countFailed", EventStore.COUNT_FAILED.get());
        r.put("countCancelled", EventStore.COUNT_CANCELLED.get());
        long total = EventStore.COUNT_DONE.get() + EventStore.COUNT_FAILED.get() + EventStore.COUNT_CANCELLED.get();
        r.put("failRate", total > 0 ? Math.round(EventStore.COUNT_FAILED.get() * 1000.0 / total) / 10.0 : 0);
        // 历史任务列表：内存(近期) + DB(落库历史) 合并去重，新的在前最多200条
        java.util.List<Map<String, Object>> history = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (EventStore.TaskMeta meta : EventStore.listHistory()) {
            Map<String, Object> m = new HashMap<>();
            m.put("sessionId", meta.sessionId);
            m.put("userId", meta.userId);
            m.put("submitMs", meta.submitMs);
            m.put("lastActiveMs", meta.lastActiveMs);
            m.put("durationMs", meta.lastActiveMs - meta.submitMs);
            m.put("status", meta.status);
            m.put("currentStep", meta.currentStep);
            m.put("modelCallsUsed", meta.status.equals("RUNNING")
                    ? utils.SafeModel.MODEL_CALLS.get() - meta.modelCallsStart  // 运行中=实时差值
                    : meta.modelCallsUsed);                                     // 已结束=定格差值
            if (meta.reviseRounds > 0) { // ★任务2：REVISE 轮次进历史表（0=首轮通过不占列）
                m.put("reviseRounds", meta.reviseRounds);
                m.put("reviseOutcome", meta.reviseOutcome);
            }
            history.add(m);
            seen.add(meta.sessionId);
        }
        for (Map<String, Object> dbm : utils.TracePersistence.listHistory(200)) {
            String sid = String.valueOf(dbm.get("sessionId"));
            if (seen.contains(sid)) continue;
            Map<String, Object> m = new HashMap<>(dbm);
            m.put("durationMs", ((Number) m.get("lastActiveMs")).longValue() - ((Number) m.get("submitMs")).longValue());
            history.add(m);
            seen.add(sid);
            if (history.size() >= 200) break;
        }
        r.put("history", history);
        // 正在运行任务的当前步骤
        java.util.List<Map<String, Object>> runningList = new ArrayList<>();
        for (Map<String, String> sessionInfo : listRunningSteps()) {
            runningList.add(new HashMap<>(sessionInfo));
        }
        r.put("runningSteps", runningList);
        r.put("success", true);
        return r;
    }

    /** 正在运行会话的当前步骤列表（sessionId → 最近事件类型） */
    private List<Map<String, String>> listRunningSteps() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, Object> s : EventStore.listSessions()) {
            if (Boolean.TRUE.equals(s.get("running"))) {
                Map<String, String> m = new HashMap<>();
                m.put("sessionId", String.valueOf(s.get("sessionId")));
                m.put("step", String.valueOf(s.getOrDefault("currentStep", "—")));
                out.add(m);
            }
        }
        return out;
    }

    /** 强制中断指定会话 */
    @PostMapping("/tasks/cancel")
    public Map<String, Object> cancelTask(@RequestParam String sessionId) {
        EventStore.requestCancel(sessionId);
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("message", "已向会话 " + sessionId + " 发送中断请求");
        return r;
    }

    /** 单个任务的调用链明细：主管(LLM/A2A) + 子Agent进程(TOOL/MCP，经 /trace 合并)，按时间排序 */
    @GetMapping("/tasks/trace")
    public Map<String, Object> taskTrace(@RequestParam("sessionId") String sessionId) {
        Map<String, Object> r = new HashMap<>();
        r.put("sessionId", sessionId);
        java.util.List<Map<String, Object>> spans = new java.util.ArrayList<>(utils.CallTrace.traceAsMaps(sessionId));
        boolean fromDb = false;
        if (spans.isEmpty()) { // ★内存无（进程重启后）：从库恢复历史 span
            spans = utils.TracePersistence.loadSpans(sessionId);
            if (!spans.isEmpty()) fromDb = true;
        }
        // 任务开始时间：过滤掉子Agent进程里同 sessionId 上次执行残留的旧 span
        long taskStartMs = managerAgent.hook.EventStore.taskStartMs(sessionId);
        if (!fromDb) { // 库中已有完整合并结果，不再实时拉子Agent
        for (String[] agent : new String[][]{{"路线", "8082"}, {"行程", "8085"}}) {
            try {
                String json = httpGetBody("http://localhost:" + agent[1] + "/trace?sessionId="
                        + java.net.URLEncoder.encode(sessionId, java.nio.charset.StandardCharsets.UTF_8), 2000);
                if (json == null) continue;
                Map<?, ?> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
                if (parsed.get("spans") instanceof java.util.List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) {
                            Object tsObj = m.get("ts");
                            long ts = tsObj instanceof Number n ? n.longValue() : 0L;
                            if (taskStartMs > 0 && ts > 0 && ts < taskStartMs) continue; // 上次执行的残留
                            Map<String, Object> mm = new HashMap<>(m.size() + 1);
                            mm.putAll((Map<String, Object>) m);
                            mm.put("agent", agent[0]); // 标记来源进程
                            spans.add(mm);
                        }
                    }
                }
            } catch (Exception ignored) { }
        }
        } // end if(!fromDb)
        // 只保留关键层：LLM/A2A/TOOL（MCP 底层每轮 ReAct 都调、量大刷屏，且成败已反映在外层 TOOL 行）
        spans.removeIf(s -> !"LLM".equals(s.get("layer")) && !"A2A".equals(s.get("layer")) && !"TOOL".equals(s.get("layer")));
        spans.sort((a, b) -> Long.compare(
                ((Number) a.getOrDefault("ts", 0L)).longValue(),
                ((Number) b.getOrDefault("ts", 0L)).longValue()));
        r.put("spans", spans);
        r.put("success", true);
        return r;
    }

    /** 单个任务的阶段事件明细（管理端透明化：解析/专家/聚合/校验/评分/审查/修订等全阶段事件文本）。
     *  事件仅存进程内存（最多100条/会话，30分钟无活动清理），重启或过久的会话可能无记录——事件 API 不创建会话。
     *  附带结构化观测：reviseSummary（任务2 REVISE 轮次/原因/结局）+ clusterStats（任务3 聚类效果统计）。 */
    @GetMapping("/tasks/events")
    public Map<String, Object> taskEvents(@RequestParam("sessionId") String sessionId) {
        Map<String, Object> r = new HashMap<>();
        r.put("sessionId", sessionId);
        List<Map<String, String>> events = EventStore.allQuiet(sessionId);
        r.put("events", events == null ? List.of() : events);
        Map<String, Object> meta = null;
        EventStore.TaskMeta live = EventStore.metaOf(sessionId);
        if (live != null) {
            meta = taskMetaBrief(live);
        } else {
            for (EventStore.TaskMeta m : EventStore.listHistory()) { // 已结束任务在历史环形队列里
                if (sessionId.equals(m.sessionId)) { meta = taskMetaBrief(m); break; }
            }
        }
        r.put("meta", meta);
        // ★结构化观测数据（EventStore extras；会话不存在=重启丢失，返回空对象而非报错）
        Map<String, Object> obs = new HashMap<>();
        Object rounds = EventStore.getExtra(sessionId, "reviseRounds");
        Object outcome = EventStore.getExtra(sessionId, "reviseOutcome");
        if (rounds != null) obs.put("reviseRounds", rounds);
        if (outcome != null) obs.put("reviseOutcome", outcome);
        Object cluster = EventStore.getExtra(sessionId, "clusterStats");
        if (cluster != null) obs.put("clusterStats", cluster);
        if (!obs.isEmpty()) r.put("obs", obs);
        r.put("success", true);
        return r;
    }

    /** 全部已记录任务的结构化 REVISE 汇总（内存+历史，新的在前）：管理端一屏看返工任务分布 */
    @GetMapping("/tasks/revise-summary")
    public Map<String, Object> reviseSummaryAll() {
        Map<String, Object> r = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        // 运行中会话
        for (Map<String, Object> s : EventStore.listSessions()) {
            String sid = String.valueOf(s.get("sessionId"));
            seen.add(sid);
            addReviseRow(rows, sid, "RUNNING");
        }
        // 历史任务（近200）
        for (EventStore.TaskMeta meta : EventStore.listHistory()) {
            if (!seen.add(meta.sessionId)) continue;
            addReviseRow(rows, meta.sessionId, meta.status);
        }
        rows.removeIf(m -> !m.containsKey("reviseRounds") && !m.containsKey("error")); // 只留有 REVISE 数据的
        r.put("rows", rows);
        r.put("success", true);
        return r;
    }

    /** 拼一行 REVISE 汇总（异常会话记 error 字段不中断列表） */
    private void addReviseRow(List<Map<String, Object>> rows, String sid, String status) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("sessionId", sid);
            m.put("status", status);
            Object rounds = EventStore.getExtra(sid, "reviseRounds");
            if (rounds instanceof List<?> l && !l.isEmpty()) m.put("reviseRounds", l);
            Object outcome = EventStore.getExtra(sid, "reviseOutcome");
            if (outcome != null) m.put("reviseOutcome", outcome);
            EventStore.TaskMeta meta = EventStore.metaOf(sid);
            if (meta == null) {
                for (EventStore.TaskMeta h : EventStore.listHistory()) {
                    if (sid.equals(h.sessionId)) { meta = h; break; }
                }
            }
            if (meta != null) {
                m.put("submitMs", meta.submitMs);
                if (meta.reviseRounds > 0) m.put("roundCount", meta.reviseRounds);
            }
            rows.add(m);
        } catch (Exception e) {
            Map<String, Object> m = new HashMap<>();
            m.put("sessionId", sid);
            m.put("error", e.getMessage());
            rows.add(m);
        }
    }

    /** TaskMeta → 前端口径简表（与 /tasks history 行字段一致） */
    private Map<String, Object> taskMetaBrief(EventStore.TaskMeta meta) {
        Map<String, Object> m = new HashMap<>();
        m.put("sessionId", meta.sessionId);
        m.put("userId", meta.userId);
        m.put("status", meta.status);
        m.put("currentStep", meta.currentStep);
        m.put("submitMs", meta.submitMs);
        m.put("lastActiveMs", meta.lastActiveMs);
        m.put("modelCallsUsed", "RUNNING".equals(meta.status)
                ? utils.SafeModel.MODEL_CALLS.get() - meta.modelCallsStart
                : meta.modelCallsUsed);
        return m;
    }

    /** HTTP GET 读 body（探活子Agent），失败返回 null */
    private String httpGetBody(String url, int timeoutMs) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs); conn.setReadTimeout(timeoutMs);
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
            String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            conn.disconnect();
            return body;
        } catch (Exception e) { return null; }
    }

    /** LLM 成本观测：各维度(任务/管线/论坛AI等)的调用次数与token消耗 + 全局汇总 */
    @GetMapping("/costs")
    public Map<String, Object> costs() {
        Map<String, Object> r = new HashMap<>();
        java.util.List<Map<String, Object>> dims = new ArrayList<>();
        long totCalls = 0, totIn = 0, totOut = 0;
        for (var e : utils.SafeModel.dimensionStatsSnapshot().entrySet()) {
            long[] s = e.getValue();
            Map<String, Object> m = new HashMap<>();
            m.put("dimension", e.getKey());
            m.put("calls", s[0]);
            m.put("inputTokens", s[1]);
            m.put("outputTokens", s[2]);
            dims.add(m);
            totCalls += s[0]; totIn += s[1]; totOut += s[2];
        }
        r.put("dimensions", dims);
        Map<String, Object> total = new HashMap<>();
        // ★total 先=主管内存值；下方子Agent探活聚合后再累加合并
        total.put("calls", totCalls);
        total.put("inputTokens", totIn);
        total.put("outputTokens", totOut);
        r.put("total", total);
        // ★成本落库：把内存累计值 upsert 到当日维度表（历史趋势基础；重启后内存清零但DB保留当日已写值）
        try {
            for (var e : utils.SafeModel.dimensionStatsSnapshot().entrySet()) {
                long[] st = e.getValue();
                utils.TracePersistence.upsertLlmCost(e.getKey(), st[0], st[1], st[2]);
            }
        } catch (Exception ignored) { }
        r.put("trend", utils.TracePersistence.costTrend(7)); // 近7日按天趋势
        // ★子Agent成本聚合：探活 /stats 拉 tokens 并进 total 与 dimensions
        for (String[] agent : new String[][]{{"RouteMakingAgent", "路线Agent", "8082"}, {"TripPlannerAgent", "行程Agent", "8085"}}) {
            try {
                java.net.URL u = new java.net.URL("http://localhost:" + agent[2] + "/stats");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(1500); conn.setReadTimeout(1500);
                if (conn.getResponseCode() == 200) {
                    Map<?, ?> json = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(conn.getInputStream(), Map.class);
                    long c = numOf(json.get("model_calls"));
                    long ti = numOf(json.get("tokens_in"));
                    long to = numOf(json.get("tokens_out"));
                    r.put("calls_" + agent[0], c);
                    r.put("tokensIn_" + agent[0], ti);
                    r.put("tokensOut_" + agent[0], to);
                    dims.add(Map.of("dimension", agent[1] + "(子)", "calls", c, "inputTokens", ti, "outputTokens", to));
                    Map<String, Object> t = total;
                    t.put("calls", ((Number) t.get("calls")).longValue() + c);
                    t.put("inputTokens", ((Number) t.get("inputTokens")).longValue() + ti);
                    t.put("outputTokens", ((Number) t.get("outputTokens")).longValue() + to);
                } else {
                    r.put("calls_" + agent[0], -1);
                    r.put("tokensIn_" + agent[0], -1);
                    r.put("tokensOut_" + agent[0], -1);
                }
                conn.disconnect();
            } catch (Exception ex) {
                r.put("calls_" + agent[0], -1);
            }
        }
        r.put("note", "进程内存值，重启清零");
        // ★缓存监控回显：命中/未中/命中率/存量（PlanCache 内存计数，重启清零）
        try {
            r.put("cache", utils.cache.PlanCache.stats());
        } catch (Exception ignored) {
            r.put("cache", Map.of("hits", 0L, "misses", 0L, "hitRate", 0.0, "size", 0));
        }
        r.put("success", true);
        return r;
    }

    /** ★Agent健康度：主管(本进程指标) + 子Agent(探活8082/8085 + 主管侧A2A成功率) 三卡综合 */
    @GetMapping("/agents/health")
    public Map<String, Object> agentsHealth() {
        Map<String, Object> r = new HashMap<>();
        r.put("agents", java.util.List.of(
                managerHealth(),
                agentHealth("RouteMakingAgent", "路线Agent", 8082),
                agentHealth("TripPlannerAgent", "行程Agent", 8085)));
        r.put("success", true);
        return r;
    }

    /** 主管Agent健康卡：本进程内指标（JVM 存活 / 模型调用 / 在途并发 / 熔断 / QPS / Token） */
    private Map<String, Object> managerHealth() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "ManagerAgent");
        m.put("label", "主管Agent");
        m.put("port", 9091);
        m.put("alive", true);
        m.put("uptimeMs", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
        m.put("modelCalls", utils.SafeModel.MODEL_CALLS.get());
        m.put("inflightActive", utils.SafeModel.getInflightActive());
        m.put("maxInflight", utils.SafeModel.getMaxInflight());
        m.put("breakerOpen", utils.CircuitBreaker.getInstance().isOpen());
        m.put("qps", utils.SafeModel.getMaxQps());
        m.put("tokensIn", utils.SafeModel.TOKENS_INPUT.get());
        m.put("tokensOut", utils.SafeModel.TOKENS_OUTPUT.get());
        // A2A 字段默认值（主管不发起 A2A 调用）：与子Agent卡片一致显示 0/0/—
        m.put("a2aCalls", 0L);
        m.put("a2aFails", 0L);
        m.put("a2aSuccessRate", -1.0);
        m.put("a2aAvgCostMs", -1L);
        m.put("light", utils.CircuitBreaker.getInstance().isOpen() ? "yellow" : "green");
        return m;
    }

    /** 单个子Agent的健康信息：进程存活 + A2A调用统计 */
    private Map<String, Object> agentHealth(String name, String label, int port) {
        Map<String, Object> a = new HashMap<>();
        a.put("name", name);
        a.put("label", label);
        a.put("port", port);
        // 1. 探活：HTTP GET /stats，2s 连接/读取超时即视为挂
        boolean alive = false;
        long uptimeMs = -1;
        int modelCalls = -1;
        long mcpCalls = -1, mcpFails = -1;
        final long[] a_lkCalls = {-1}, a_lkFails = {-1}, a_lkCostMs = {-1}; // lambda 内赋值用数组
        final long[] a_toolCalls = {-1}, a_toolFails = {-1}, a_toolCostMs = {-1};
        Map<String, long[]> mcpByName = new java.util.LinkedHashMap<>();
        try {
            java.net.URL url = new java.net.URL("http://localhost:" + port + "/stats");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                alive = true;
                String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                Map<?, ?> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
                if (json.get("uptimeMs") instanceof Number n) {
                    uptimeMs = n.longValue();
                }
                if (json.get("model_calls") instanceof Number n) {
                    modelCalls = n.intValue();
                }
                if (json.get("mcp_calls") instanceof Number n) mcpCalls = n.longValue();
                if (json.get("mcp_fails") instanceof Number n) mcpFails = n.longValue();
                if (json.get("lk_calls") instanceof Number n) a_lkCalls[0] = n.longValue();
                if (json.get("lk_fails") instanceof Number n) a_lkFails[0] = n.longValue();
                if (json.get("lk_cost_ms") instanceof Number n) a_lkCostMs[0] = n.longValue();
                if (json.get("tool_calls") instanceof Number n) a_toolCalls[0] = n.longValue();
                if (json.get("tool_fails") instanceof Number n) a_toolFails[0] = n.longValue();
                if (json.get("tool_cost_ms") instanceof Number n) a_toolCostMs[0] = n.longValue();
                if (json.get("mcp_by_name") instanceof Map<?, ?> b) {
                    b.forEach((k, v) -> {
                        if (v instanceof java.util.List<?> arr && arr.size() >= 2) {
                            mcpByName.put(String.valueOf(k), new long[]{
                                    ((Number) arr.get(0)).longValue(), ((Number) arr.get(1)).longValue()});
                        }
                    });
                }
            }
            conn.disconnect();
        } catch (Exception ignored) { }
        a.put("alive", alive);
        a.put("uptimeMs", uptimeMs);
        a.put("modelCalls", modelCalls);
        a.put("mcpCalls", mcpCalls);
        a.put("mcpFails", mcpFails);
        a.put("mcpByName", mcpByName);
        a.put("lkCalls", a_lkCalls[0]);
        a.put("lkFails", a_lkFails[0]);
        a.put("lkAvgMs", a_lkCalls[0] > 0 ? a_lkCostMs[0] / a_lkCalls[0] : -1);
        a.put("toolCalls", a_toolCalls[0]);
        a.put("toolFails", a_toolFails[0]);
        a.put("toolAvgMs", a_toolCalls[0] > 0 ? a_toolCostMs[0] / a_toolCalls[0] : -1);
        // 2. 主管侧 A2A 调用统计（RemoteAgentTool 埋点累计）
        long calls = managerAgent.tool.RemoteAgentTool.AGENT_CALLS
                .getOrDefault(name, new java.util.concurrent.atomic.AtomicLong()).get();
        long fails = managerAgent.tool.RemoteAgentTool.AGENT_FAILS
                .getOrDefault(name, new java.util.concurrent.atomic.AtomicLong()).get();
        long costMs = managerAgent.tool.RemoteAgentTool.AGENT_COST_MS
                .getOrDefault(name, new java.util.concurrent.atomic.AtomicLong()).get();
        a.put("a2aCalls", calls);
        a.put("a2aFails", fails);
        a.put("a2aSuccessRate", calls > 0 ? Math.round((calls - fails) * 1000.0 / calls) / 10.0 : -1); // 百分比，-1=无调用
        a.put("a2aAvgCostMs", calls > 0 ? costMs / calls : -1);
        // 3. 综合灯色：绿=存活且成功率≥90%；黄=存活但失败率高；红=探活不通；灰=存活但从未被调用
        String light;
        if (!alive) light = "red";
        else if (calls == 0) light = "gray";
        else light = (calls - fails) * 100 >= calls * 90 ? "green" : "yellow";
        a.put("light", light);
        return a;
    }

    /** ★工具层细粒度观测：各子Agent的综合工具/知识库/内部MCP工具 调用数与成功率（探活 /stats 聚合） */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        Map<String, Object> r = new HashMap<>();
        java.util.List<Map<String, Object>> agents = new ArrayList<>();
        for (String[] agent : new String[][]{{"RouteMakingAgent", "路线Agent", "8082"}, {"TripPlannerAgent", "行程Agent", "8085"}}) {
            Map<String, Object> a = new HashMap<>();
            a.put("name", agent[0]);
            a.put("label", agent[1]);
            try {
                String body = httpGetBody("http://localhost:" + agent[2] + "/stats", 2000);
                if (body == null) {
                    a.put("alive", false);
                    agents.add(a);
                    continue;
                }
                a.put("alive", true);
                Map<?, ?> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
                long tc = numOf(json.get("tool_calls")), tf = numOf(json.get("tool_fails"));
                a.put("toolCalls", tc); a.put("toolFails", tf);
                a.put("toolRate", tc > 0 ? Math.round((tc - tf) * 1000.0 / tc) / 10.0 : -1);
                long lc = numOf(json.get("lk_calls")), lf = numOf(json.get("lk_fails"));
                a.put("lkCalls", lc); a.put("lkFails", lf);
                a.put("lkRate", lc > 0 ? Math.round((lc - lf) * 1000.0 / lc) / 10.0 : -1);
                long mc = numOf(json.get("mcp_calls")), mf = numOf(json.get("mcp_fails"));
                a.put("mcpCalls", mc); a.put("mcpFails", mf);
                a.put("mcpRate", mc > 0 ? Math.round((mc - mf) * 1000.0 / mc) / 10.0 : -1);
                java.util.List<Map<String, Object>> byName = new ArrayList<>();
                if (json.get("mcp_by_name") instanceof Map<?, ?> b) {
                    b.forEach((k, v) -> {
                        long c = -1, f = -1;
                        if (v instanceof java.util.List<?> arr && arr.size() >= 2) {
                            c = ((Number) arr.get(0)).longValue();
                            f = ((Number) arr.get(1)).longValue();
                        } else if (v instanceof Number[] n2 && n2.length >= 2) {
                            c = n2[0].longValue(); f = n2[1].longValue();
                        }
                        if (c < 0) return;
                        Map<String, Object> mm = new HashMap<>();
                        mm.put("tool", String.valueOf(k));
                        mm.put("calls", c); mm.put("fails", f);
                        mm.put("rate", c > 0 ? Math.round((c - f) * 1000.0 / c) / 10.0 : -1);
                        byName.add(mm);
                    });
                    byName.sort((x, y) -> Long.compare(((Number) y.get("calls")).longValue(), ((Number) x.get("calls")).longValue()));
                }
                a.put("mcpByName", byName);
            } catch (Exception e) {
                a.put("alive", false);
            }
            agents.add(a);
        }
        r.put("agents", agents);
        r.put("success", true);
        return r;
    }

    /** 数值安全转换：非数字返回 -1 */
    private static long numOf(Object o) {
        return o instanceof Number n ? n.longValue() : -1;
    }

    // ==================== 模型监控 ====================

    /** 熔断器状态 / 失败计数 / 主备模型 / 限速 QPS */
    // ==================== 模型配置热更 ====================

    /** 读当前模型配置（key 打码显示；含主模型/备用模型/Python侧三组） */
    @GetMapping("/model-config")
    public Map<String, Object> getModelConfig() {
        Map<String, Object> r = new java.util.HashMap<>();
        String jk = utils.ModelConfigHolder.apiKey();
        r.put("java", Map.of(
                "url", utils.ModelConfigHolder.baseUrl(),
                "name", utils.ModelConfigHolder.modelName(),
                "keyMasked", maskKey(jk)));
        String fk = utils.ModelConfigHolder.fbApiKey();
        r.put("fallback", Map.of(
                "url", utils.ModelConfigHolder.fbBaseUrl(),
                "name", utils.ModelConfigHolder.fbModelName(),
                "keyMasked", maskKey(fk)));
        Map<String, Object> py = readPyLlmConfig();
        py.put("keyMasked", maskKey((String) py.get("key")));
        py.remove("key");
        r.put("python", py);
        Map<String, Object> orchestration = utils.NacosModelConfig.orchestrationConfig();
        RemoteTimeout.configure(orchestration);
        r.put("orchestration", Map.of(
                "maxConcurrentTasks", AppController.maxConcurrent(),
                "remoteTimeoutSeconds", RemoteTimeout.currentSeconds(),
                "source", orchestration != null && orchestration.get("maxConcurrentTasks") != null
                        ? "nacos" : "env"));
        Map<String, Object> timeoutConfig = utils.NacosModelConfig.timeoutsConfig();
        r.put("timeouts", Map.of(
                "defaultSeconds", utils.ModelTimeouts.defaultMillis() / 1000,
                "moderationSeconds", utils.ModelTimeouts.moderationMillis() / 1000,
                "aiAnswerSeconds", utils.ModelTimeouts.aiAnswerMillis() / 1000,
                "tripEditSeconds", utils.ModelTimeouts.tripEditMillis() / 1000,
                "source", timeoutConfig.isEmpty() ? "default" : "nacos"));
        r.put("success", true);
        return r;
    }

    /** 保存模型配置：先发布 Nacos 配置，成功后更新本进程并写入 Python 本地兜底缓存。 */
    @PostMapping("/model-config")
    public Map<String, Object> saveModelConfig(@RequestParam(required = false) String javaUrl,
                                               @RequestParam(required = false) String javaKey,
                                               @RequestParam(required = false) String javaName,
                                               @RequestParam(required = false) String fbUrl,
                                               @RequestParam(required = false) String fbKey,
                                               @RequestParam(required = false) String fbName,
                                               @RequestParam(required = false) String pyUrl,
                                               @RequestParam(required = false) String pyKey,
                                               @RequestParam(required = false) String pyName,
                                               @RequestParam(required = false) Integer maxConcurrentTasks,
                                               @RequestParam(required = false) Integer remoteTimeoutSeconds,
                                               @RequestParam(required = false) Integer defaultTimeoutSeconds,
                                               @RequestParam(required = false) Integer moderationTimeoutSeconds,
                                               @RequestParam(required = false) Integer aiAnswerTimeoutSeconds,
                                               @RequestParam(required = false) Integer tripEditTimeoutSeconds,
                                               @RequestParam(required = false) Double qps,
                                               @RequestParam(required = false) Integer maxInflight,
                                               @RequestParam(required = false) Integer maxRetries,
                                               @RequestParam(required = false) Long retryBaseMs,
                                               @RequestParam(required = false) Integer breakerFailureThreshold,
                                               @RequestParam(required = false) Long breakerWindowMs,
                                               @RequestParam(required = false) Long breakerCoolDownMs) {
        Map<String, Object> r = new java.util.HashMap<>();
        try {
            if (maxConcurrentTasks != null
                    && (maxConcurrentTasks < ConcurrencyGate.MIN_LIMIT
                    || maxConcurrentTasks > ConcurrencyGate.HARD_LIMIT)) {
                r.put("success", false);
                r.put("message", "保存失败：最大并发任务必须在 "
                        + ConcurrencyGate.MIN_LIMIT + "-" + ConcurrencyGate.HARD_LIMIT + " 之间");
                return r;
            }
            if (remoteTimeoutSeconds != null
                    && (remoteTimeoutSeconds < RemoteTimeout.MIN_SECONDS
                    || remoteTimeoutSeconds > RemoteTimeout.MAX_SECONDS)) {
                r.put("success", false);
                r.put("message", "保存失败：远程调用超时必须在 "
                        + RemoteTimeout.MIN_SECONDS + "-" + RemoteTimeout.MAX_SECONDS + " 秒之间");
                return r;
            }
            Integer[] directTimeouts = {defaultTimeoutSeconds, moderationTimeoutSeconds,
                    aiAnswerTimeoutSeconds, tripEditTimeoutSeconds};
            for (Integer timeout : directTimeouts) {
                if (timeout != null && (timeout < utils.ModelTimeouts.MIN_SECONDS
                        || timeout > utils.ModelTimeouts.MAX_SECONDS)) {
                    r.put("success", false);
                    r.put("message", "保存失败：直连模型超时必须在 "
                            + utils.ModelTimeouts.MIN_SECONDS + "-" + utils.ModelTimeouts.MAX_SECONDS + " 秒之间");
                    return r;
                }
            }
            boolean javaChanged = (javaUrl != null && !javaUrl.isBlank())
                    || (javaKey != null && !javaKey.isBlank())
                    || (javaName != null && !javaName.isBlank());
            boolean fbChanged = (fbUrl != null && !fbUrl.isBlank())
                    || (fbKey != null && !fbKey.isBlank())
                    || (fbName != null && !fbName.isBlank());
            boolean pyChanged = pyUrl != null && !pyUrl.isBlank() || pyKey != null && !pyKey.isBlank()
                    || pyName != null && !pyName.isBlank();
            boolean orchestrationChanged = maxConcurrentTasks != null || remoteTimeoutSeconds != null;
            boolean timeoutsChanged = defaultTimeoutSeconds != null || moderationTimeoutSeconds != null
                    || aiAnswerTimeoutSeconds != null || tripEditTimeoutSeconds != null;
            boolean limitsChanged = qps != null || maxInflight != null || maxRetries != null || retryBaseMs != null
                    || breakerFailureThreshold != null || breakerWindowMs != null || breakerCoolDownMs != null;
            if (!javaChanged && !fbChanged && !pyChanged && !orchestrationChanged
                    && !timeoutsChanged && !limitsChanged) {
                r.put("success", true);
                r.put("message", "没有需要保存的修改");
                return r;
            }

            Map<String, Object> java = new java.util.HashMap<>(utils.NacosModelConfig.javaConfig());
            putIfPresent(java, "url", javaUrl);
            putIfPresent(java, "key", javaKey);
            putIfPresent(java, "name", javaName);

            Map<String, Object> fallback = new java.util.HashMap<>(utils.NacosModelConfig.fallbackConfig());
            putIfPresent(fallback, "url", fbUrl);
            putIfPresent(fallback, "key", fbKey);
            putIfPresent(fallback, "name", fbName);

            Map<String, Object> python = new java.util.HashMap<>(readPyLlmConfig());
            putIfPresent(python, "url", pyUrl);
            putIfPresent(python, "key", pyKey);
            putIfPresent(python, "name", pyName);

            Map<String, Object> config = new java.util.LinkedHashMap<>();
            config.put("java", java);
            config.put("fallback", fallback);
            config.put("python", python);
            Map<String, Object> limits = new java.util.LinkedHashMap<>();
            limits.put("qps", qps != null ? qps : utils.SafeModel.getMaxQps());
            limits.put("maxInflight", maxInflight != null ? maxInflight : utils.SafeModel.getMaxInflight()); // ★在途并发
            limits.put("maxRetries", maxRetries != null ? maxRetries : utils.SafeModel.getMaxRetries());
            limits.put("retryBaseMs", retryBaseMs != null ? retryBaseMs : utils.SafeModel.getRetryBaseMs());
            limits.put("breakerFailureThreshold", breakerFailureThreshold != null ? breakerFailureThreshold : utils.CircuitBreaker.getFailureThreshold());
            limits.put("breakerWindowMs", breakerWindowMs != null ? breakerWindowMs : utils.CircuitBreaker.getWindowMs());
            limits.put("breakerCoolDownMs", breakerCoolDownMs != null ? breakerCoolDownMs : utils.CircuitBreaker.getCoolDownMs());
            config.put("limits", limits);
            Map<String, Object> orchestration = utils.NacosModelConfig.orchestrationConfig();
            if (orchestration == null) {
                orchestration = new java.util.LinkedHashMap<>();
            }
            if (orchestrationChanged) {
                if (maxConcurrentTasks != null) {
                    orchestration.put("maxConcurrentTasks", maxConcurrentTasks);
                }
                if (remoteTimeoutSeconds != null) {
                    orchestration.put("remoteTimeoutSeconds", remoteTimeoutSeconds);
                }
            }
            if (!orchestration.isEmpty()) {
                config.put("orchestration", orchestration);
            }
            if (timeoutsChanged) {
                Map<String, Object> timeouts = new java.util.LinkedHashMap<>(
                        utils.NacosModelConfig.timeoutsConfig());
                if (defaultTimeoutSeconds != null) timeouts.put("defaultSeconds", defaultTimeoutSeconds);
                if (moderationTimeoutSeconds != null) timeouts.put("moderationSeconds", moderationTimeoutSeconds);
                if (aiAnswerTimeoutSeconds != null) timeouts.put("aiAnswerSeconds", aiAnswerTimeoutSeconds);
                if (tripEditTimeoutSeconds != null) timeouts.put("tripEditSeconds", tripEditTimeoutSeconds);
                config.put("timeouts", timeouts);
            }
            config.put("updatedAt", Instant.now().toString());
            utils.NacosModelConfig.publish(config);

            utils.ModelConfigHolder.update(javaUrl, javaKey, javaName);
            utils.ModelConfigHolder.updateFallback(fbUrl, fbKey, fbName);
            if (javaChanged || fbChanged) managerAgent.tool.LlmClientReset.reset();
            savePyLlmConfig(pyUrl, pyKey, pyName);

            r.put("success", true);
            r.put("message", "配置已发布到Nacos；相关进程监听热更，无需重启");
        } catch (Exception e) {
            r.put("success", false);
            r.put("message", "保存失败：" + e.getMessage());
        }
        return r;
    }

    private void putIfPresent(Map<String, Object> config, String key, String value) {
        if (value != null && !value.isBlank()) config.put(key, value.trim());
    }

    /** 打码 key */
    private String maskKey(String k) {
        return (k == null || k.length() < 10) ? "" : k.substring(0, 6) + "****" + k.substring(k.length() - 4);
    }

    /** Python 侧统一配置文件 data-pipeline/llm_config.json */
    private java.io.File pyConfigFile() {
        return new java.io.File(projectRoot(), "data-pipeline/llm_config.json");
    }

    /**
     * 项目根目录不能依赖 user.dir：IDE 或手动启动 jar 时，工作目录可能在 target。
     * 向上查找同时包含 backend 与 data-pipeline 的目录，本地和云端部署都适用。
     */
    private java.io.File projectRoot() {
        try {
            String configured = Config.get("PROJECT_ROOT", "");
            if (!configured.isBlank()) {
                java.io.File configuredDir = new java.io.File(configured).getCanonicalFile();
                if (new java.io.File(configuredDir, "data-pipeline").isDirectory()) return configuredDir;
            }
            java.io.File dir = new java.io.File(System.getProperty("user.dir")).getCanonicalFile();
            while (dir != null) {
                if (new java.io.File(dir, "data-pipeline").isDirectory()
                        && new java.io.File(dir, "backend").isDirectory()) {
                    return dir;
                }
                dir = dir.getParentFile();
            }
        } catch (Exception e) {
            log.warn("解析项目根目录失败: {}", e.getMessage());
        }
        return new java.io.File(System.getProperty("user.dir"));
    }

    private Map<String, Object> readPyLlmConfig() {
        Map<String, Object> m = new java.util.HashMap<>();
        Map<String, Object> remote = utils.NacosModelConfig.pythonConfig();
        if (remote != null) {
            m.putAll(remote);
        } else {
            try {
                java.io.File f = pyConfigFile();
                if (f.exists()) {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    m = om.readValue(f, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
                }
            } catch (Exception ignored) { }
        }
        m.putIfAbsent("url", "https://openrouter.ai/api/v1");
        m.putIfAbsent("key", "");
        m.putIfAbsent("name", "stealth/ox-alpha");
        return m;
    }

    private void savePyLlmConfig(String url, String key, String name) throws Exception {
        Map<String, Object> cur = readPyLlmConfig();
        if (url != null && !url.isBlank()) cur.put("url", url.trim());
        if (key != null && !key.isBlank()) cur.put("key", key.trim());
        if (name != null && !name.isBlank()) cur.put("name", name.trim());
        new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValue(pyConfigFile(), cur);
    }


    @GetMapping("/breaker")
    public Map<String, Object> breaker() {
        CircuitBreaker cb = CircuitBreaker.getInstance();
        Map<String, Object> r = new HashMap<>();
        r.put("state", cb.getState().name());
        r.put("consecutiveFailures", cb.getConsecutiveFailures());
        r.put("primaryModel", utils.ModelConfigHolder.modelName());
        r.put("fallbackModel", utils.ModelConfigHolder.fbModelName());
        r.put("maxQps", utils.SafeModel.getMaxQps());
        r.put("maxInflight", utils.SafeModel.getMaxInflight()); // ★在途并发上限
        r.put("inflightActive", utils.SafeModel.getInflightActive()); // 当前在途数
        r.put("maxRetries", utils.SafeModel.getMaxRetries());
        r.put("retryBaseMs", utils.SafeModel.getRetryBaseMs());
        r.put("breakerFailureThreshold", utils.CircuitBreaker.getFailureThreshold());
        r.put("breakerWindowMs", utils.CircuitBreaker.getWindowMs());
        r.put("breakerCoolDownMs", utils.CircuitBreaker.getCoolDownMs());
        r.put("success", true);
        return r;
    }

    // ==================== Agent 工具开关 ====================

    /** Read effective agent runtime switches. Missing Nacos config means all defaults are on. */
    @GetMapping("/agent-config")
    public Map<String, Object> getAgentConfig() {
        Map<String, Object> r = new HashMap<>();
        Map<String, Object> tools = new HashMap<>();
        tools.put("amap", AgentRuntimeConfig.toolEnabled("amap", true));
        tools.put("localKnowledge", AgentRuntimeConfig.toolEnabled("localKnowledge", true));
        tools.put("remoteRouteAgent", AgentRuntimeConfig.toolEnabled("remoteRouteAgent", true));
        tools.put("remoteTripAgent", AgentRuntimeConfig.toolEnabled("remoteTripAgent", true));
        r.put("tools", tools);
        r.put("source", AgentRuntimeConfig.loaded() ? "nacos" : "default");
        r.put("restartInProgress", agentRestarting.get());
        r.put("success", true);
        return r;
    }

    /** Save switches to Nacos. Registration-time switches require rebuilding child toolkits. */
    @PostMapping("/agent-config")
    public Map<String, Object> saveAgentConfig(@RequestParam Boolean amap,
                                               @RequestParam Boolean localKnowledge,
                                               @RequestParam Boolean remoteRouteAgent,
                                               @RequestParam Boolean remoteTripAgent) {
        Map<String, Object> r = new HashMap<>();
        if (amap == null || localKnowledge == null || remoteRouteAgent == null || remoteTripAgent == null) {
            r.put("success", false);
            r.put("message", "保存失败：工具开关不能为空");
            return r;
        }
        try {
            boolean oldAmap = AgentRuntimeConfig.toolEnabled("amap", true);
            boolean oldLocalKnowledge = AgentRuntimeConfig.toolEnabled("localKnowledge", true);
            boolean restartRequired = oldAmap != amap || oldLocalKnowledge != localKnowledge;

            Map<String, Object> config = new java.util.LinkedHashMap<>(AgentRuntimeConfig.currentConfig());
            Map<String, Object> tools = new java.util.LinkedHashMap<>(AgentRuntimeConfig.toolsConfig());
            tools.put("amap", amap);
            tools.put("localKnowledge", localKnowledge);
            tools.put("remoteRouteAgent", remoteRouteAgent);
            tools.put("remoteTripAgent", remoteTripAgent);
            config.put("tools", tools);
            config.put("updatedAt", Instant.now().toString());
            AgentRuntimeConfig.publish(config);

            r.put("success", true);
            r.put("restartRequired", restartRequired);
            if (!restartRequired) {
                r.put("restartTriggered", false);
                r.put("message", "配置已保存到Nacos，远程Agent开关调用前热生效");
            } else if (startAgentRestart()) {
                r.put("restartTriggered", true);
                r.put("message", "配置已保存到Nacos；正在重启路线/行程子Agent（8082/8085）");
            } else {
                r.put("restartTriggered", false);
                r.put("message", "配置已保存到Nacos；已有子Agent重启任务在进行中");
            }
        } catch (Exception e) {
            log.warn("Save agent runtime config failed: {}", e.getMessage(), e);
            r.put("success", false);
            r.put("message", "保存失败：" + e.getMessage());
        }
        return r;
    }

    // ==================== Agent 提示词与工具描述 ====================

    /** Read prompts managed by Nacos Prompt management; the admin UI is read-only. */
    @GetMapping("/agent-prompts")
    public Map<String, Object> getAgentPrompts() {
        Map<String, Object> r = new HashMap<>();
        Map<String, Object> prompts = new LinkedHashMap<>();
        Map<String, String> labels = AgentPrompts.labels();
        AgentPrompts.defaults().forEach((key, defaultValue) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("label", labels.getOrDefault(key, key));
            item.put("value", AgentPromptStore.prompt(key, defaultValue));
            item.put("defaultValue", defaultValue);
            item.put("nacosPromptKey", AgentPromptStore.promptKey(key));
            item.put("customized", AgentPromptStore.customized(key));
            item.put("editable", false);
            prompts.put(key, item);
        });
        r.put("prompts", prompts);
        r.put("source", AgentPromptStore.source());
        r.put("success", true);
        return r;
    }
    // ==================== 高德凭证 ====================

    /** Show masked AMap keys; blank inputs on save mean "keep the current key". */
    @GetMapping("/amap-credentials")
    public Map<String, Object> getAmapCredentials() {
        Map<String, Object> r = new HashMap<>();
        String webKey = ExternalServices.amapKey();
        String mcpKey = ExternalServices.amapMcpKey();
        Map<String, Object> stored = AgentRuntimeConfig.credentialsConfig();
        r.put("webKeyMasked", maskKey(webKey));
        r.put("webKeyConfigured", webKey != null && !webKey.isBlank());
        r.put("mcpKeyMasked", maskKey(mcpKey));
        r.put("mcpKeyConfigured", mcpKey != null && !mcpKey.isBlank());
        r.put("source", stored.containsKey("amapKey") || stored.containsKey("amapMcpKey")
                ? "nacos" : "env");
        r.put("restartInProgress", agentRestarting.get());
        r.put("success", true);
        return r;
    }

    /** Save AMap keys to Nacos. A changed MCP key requires rebuilding child MCP clients. */
    @PostMapping("/amap-credentials")
    public Map<String, Object> saveAmapCredentials(
            @RequestParam(required = false) String webKey,
            @RequestParam(required = false) String mcpKey) {
        Map<String, Object> r = new HashMap<>();
        try {
            webKey = webKey == null ? "" : webKey.trim();
            mcpKey = mcpKey == null ? "" : mcpKey.trim();
            if (webKey.isBlank() && mcpKey.isBlank()) {
                r.put("success", true);
                r.put("message", "没有需要保存的高德Key修改");
                return r;
            }

            String oldWebKey = ExternalServices.amapKey();
            String oldMcpKey = ExternalServices.amapMcpKey();
            Map<String, Object> credentials = new java.util.LinkedHashMap<>(AgentRuntimeConfig.credentialsConfig());
            if (!webKey.isBlank()) credentials.put("amapKey", webKey);
            if (!mcpKey.isBlank()) credentials.put("amapMcpKey", mcpKey);

            String storedWeb = String.valueOf(credentials.getOrDefault("amapKey", "")).trim();
            String storedMcp = String.valueOf(credentials.getOrDefault("amapMcpKey", "")).trim();
            String newWebKey = storedWeb.isBlank() ? oldWebKey : storedWeb;
            String newMcpKey = storedMcp.isBlank()
                    ? (storedWeb.isBlank() ? oldMcpKey : storedWeb)
                    : storedMcp;
            boolean webChanged = !newWebKey.equals(oldWebKey);
            boolean mcpChanged = !newMcpKey.equals(oldMcpKey);
            if (!webChanged && !mcpChanged) {
                r.put("success", true);
                r.put("restartRequired", false);
                r.put("restartTriggered", false);
                r.put("message", "高德Key未变化");
                return r;
            }

            Map<String, Object> config = new java.util.LinkedHashMap<>(AgentRuntimeConfig.currentConfig());
            config.put("credentials", credentials);
            config.put("updatedAt", Instant.now().toString());
            AgentRuntimeConfig.publish(config);

            r.put("success", true);
            r.put("restartRequired", mcpChanged);
            if (!mcpChanged) {
                r.put("restartTriggered", false);
                r.put("message", "高德Key已保存到Nacos，主管接口热生效");
            } else if (startAgentRestart()) {
                r.put("restartTriggered", true);
                r.put("message", "高德Key已保存到Nacos；MCP Key变化，正在重启路线/行程子Agent（8082/8085）");
            } else {
                r.put("restartTriggered", false);
                r.put("message", "高德Key已保存到Nacos；已有子Agent重启任务在进行中");
            }
        } catch (Exception e) {
            log.warn("Save AMap credentials failed: {}", e.getMessage(), e);
            r.put("success", false);
            r.put("message", "保存失败：" + e.getMessage());
        }
        return r;
    }

    /** The manager owns the restart so a child process never needs to kill itself. */
    private boolean startAgentRestart() {
        if (!agentRestarting.compareAndSet(false, true)) return false;
        Thread thread = new Thread(() -> {
            try {
                java.io.File root = projectRoot();
                boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
                java.io.File script = new java.io.File(root, windows ? "restart-agents.ps1" : "restart-agents.sh");
                if (!script.isFile()) {
                    log.error("Agent restart script not found: {}", script.getAbsolutePath());
                    return;
                }
                java.io.File logDir = new java.io.File(root, "logs");
                if (!logDir.isDirectory() && !logDir.mkdirs()) {
                    log.error("Agent restart log directory cannot be created: {}", logDir.getAbsolutePath());
                    return;
                }
                java.io.File logFile = new java.io.File(logDir, "agent-tool-restart.log");
                ProcessBuilder processBuilder;
                if (windows) {
                    String windir = System.getenv("WINDIR");
                    java.io.File powershell = windir == null
                            ? null
                            : new java.io.File(windir, "System32\\WindowsPowerShell\\v1.0\\powershell.exe");
                    String executable = powershell != null && powershell.isFile()
                            ? powershell.getAbsolutePath()
                            : "powershell";
                    processBuilder = new ProcessBuilder(executable, "-NoProfile", "-NonInteractive",
                            "-ExecutionPolicy", "Bypass", "-File", script.getCanonicalPath());
                } else {
                    processBuilder = new ProcessBuilder(bashExecutable(),
                            script.getCanonicalPath().replace('\\', '/'));
                }
                processBuilder.directory(root);
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFile));
                log.info("Restarting child agents using {}", processBuilder.command());
                Process process = processBuilder.start();
                int code = process.waitFor();
                if (code == 0) {
                    log.info("Child agents restarted after agent tool switch change");
                } else {
                    log.error("Child agent restart failed with code {}: {}", code, logFile.getAbsolutePath());
                }
            } catch (Exception e) {
                log.error("Child agent restart failed", e);
            } finally {
                agentRestarting.set(false);
            }
        }, "agent-tool-restart");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** Windows System32 bash is WSL and cannot use drive paths; prefer Git Bash when available. */
    private String bashExecutable() {
        String configured = Config.get("BASH_BIN", "");
        if (!configured.isBlank()) return configured;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return "bash";

        for (String key : new String[]{"GIT_BASH_PATH", "HERMES_GIT_BASH_PATH"}) {
            String configuredPath = System.getenv(key);
            if (configuredPath != null && new java.io.File(configuredPath).isFile()) {
                return configuredPath;
            }
        }

        String gitBash = bashExecutableFromGitWhere();
        if (gitBash != null) return gitBash;

        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String path : pathEnv.split(";")) {
                if (path.isBlank()) continue;
                java.io.File directory = new java.io.File(path);
                roots.add(directory.getPath());
                java.io.File parent = directory.getParentFile();
                if (parent != null) roots.add(parent.getPath());
            }
        }
        for (String key : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) roots.add(value);
        }
        roots.add("C:\\Program Files");
        roots.add("C:\\Program Files (x86)");

        for (String root : roots) {
            for (String relative : new String[]{"Git\\bin\\bash.exe", "Git\\usr\\bin\\bash.exe",
                    "bin\\bash.exe", "usr\\bin\\bash.exe"}) {
                java.io.File candidate = new java.io.File(root, relative);
                if (candidate.isFile()) return candidate.getAbsolutePath();
            }
        }

        log.warn("Windows Git Bash not found; falling back to PATH bash. Set BASH_BIN to Git Bash's bash.exe");
        return "bash";
    }

    /** Git is often on PATH while bash.exe is not; derive Git Bash from the located git.exe. */
    private String bashExecutableFromGitWhere() {
        try {
            Process process = new ProcessBuilder("where.exe", "git").start();
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    process.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    java.io.File git = new java.io.File(line.trim());
                    if (!git.isFile()) continue;

                    java.io.File directory = git.getParentFile();
                    for (int depth = 0; depth < 3 && directory != null; depth++) {
                        for (String relative : new String[]{"bin\\bash.exe", "usr\\bin\\bash.exe"}) {
                            java.io.File candidate = new java.io.File(directory, relative);
                            if (candidate.isFile()) return candidate.getAbsolutePath();
                        }
                        directory = directory.getParentFile();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Cannot locate Git Bash through where.exe git", e);
        }
        return null;
    }

    // ==================== 数据管理 ====================

    /** 用户列表（含角色） */
    @GetMapping("/users")
    public Map<String, Object> users() {
        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "SELECT id, username, role FROM users ORDER BY id");
        for (Map<String, Object> u : users) {
            u.putIfAbsent("role", "user"); // 老库无 role 列时默认 user
        }
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("users", users);
        return r;
    }

    /** 删除用户（连同其名下行程、发帖、点赞、偏好、画像一起删） */
    @DeleteMapping("/users")
    public Map<String, Object> deleteUser(@RequestParam Long id) {
        jdbcTemplate.update("DELETE FROM trip_plans WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM posts WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM post_likes WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM user_preferences WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM user_profiles_tags WHERE user_id = ?", id); // 同步清理画像标签
        int rows = jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        Map<String, Object> r = new HashMap<>();
        r.put("success", rows > 0);
        r.put("message", rows > 0 ? "已删除用户及其名下行程" : "用户不存在");
        return r;
    }

    /** 行程列表（关联用户名，预览内容） */
    @GetMapping("/plans")
    public Map<String, Object> plans() {
        List<Map<String, Object>> plans = jdbcTemplate.queryForList(
                "SELECT p.id, p.user_id, u.username, p.city, p.days, " +
                        "LEFT(p.plan_content, 200) AS preview, p.create_time " +
                        "FROM trip_plans p LEFT JOIN users u ON p.user_id = u.id ORDER BY p.id DESC");
        // 城市归一化："成都三日"→"成都"，匹配标准城市库
        java.util.List<String> stdCities = jdbcTemplate.queryForList(
                "SELECT DISTINCT city FROM attractions WHERE city IS NOT NULL AND city != ''", String.class);
        for (Map<String, Object> pl : plans) {
            Object c = pl.get("city");
            if (c == null) continue;
            String raw = String.valueOf(c).trim();
            if (stdCities.contains(raw)) continue;
            String best = null;
            for (String s : stdCities) {
                if (raw.startsWith(s) && (best == null || s.length() > best.length())) best = s;
            }
            if (best != null) pl.put("city", best);
        }
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("plans", plans);
        return r;
    }

    /** 删除指定行程 */
    @DeleteMapping("/plans")
    public Map<String, Object> deletePlan(@RequestParam Long id) {
        int rows = jdbcTemplate.update("DELETE FROM trip_plans WHERE id = ?", id);
        Map<String, Object> r = new HashMap<>();
        r.put("success", rows > 0);
        r.put("message", rows > 0 ? "已删除行程" : "行程不存在");
        return r;
    }
    // ==================== 内容审核（阶段三） ====================

    /** 审核队列：待处理举报 + AI 审核 review/block 记录 + 被隐藏内容 */
    @GetMapping("/moderation/queue")
    public Map<String, Object> moderationQueue() {
        Map<String, Object> r = new HashMap<>();
        r.put("reports", jdbcTemplate.queryForList(
                "SELECT r.id, r.reporter_id, u.username AS reporter, r.target_type, r.target_id, " +
                "r.reason, r.status, r.created_at FROM reports r LEFT JOIN users u ON u.id = r.reporter_id " +
                "WHERE r.status = 0 ORDER BY r.created_at DESC LIMIT 100"));
        r.put("records", jdbcTemplate.queryForList(
                "SELECT m.id, m.target_type, m.target_id, m.verdict, m.reason, m.model, m.created_at " +
                "FROM moderation_records m WHERE m.verdict > 0 ORDER BY m.created_at DESC LIMIT 100"));
        r.put("hiddenPosts", jdbcTemplate.queryForList(
                "SELECT p.id, p.title, p.user_id, u.username, p.created_at FROM posts p " +
                "LEFT JOIN users u ON u.id = p.user_id WHERE p.status = 0 ORDER BY p.created_at DESC LIMIT 100"));
        r.put("hiddenComments", jdbcTemplate.queryForList(
                "SELECT c.id, c.content, c.post_id, c.user_id, u.username, c.created_at FROM comments c " +
                "LEFT JOIN users u ON u.id = c.user_id WHERE c.status = 0 ORDER BY c.created_at DESC LIMIT 100"));
        r.put("success", true);
        return r;
    }

    /** 处理举报（标记已处理） */
    @PutMapping("/moderation/resolve")
    public Map<String, Object> resolveReport(@RequestParam Long id) {
        int rows = jdbcTemplate.update("UPDATE reports SET status = 1 WHERE id = ?", id);
        Map<String, Object> r = new HashMap<>();
        r.put("success", rows > 0);
        r.put("message", rows > 0 ? "举报已标记处理" : "举报不存在");
        return r;
    }

    /** 隐藏/恢复内容（targetType: 1帖子 2评论；action: hide|restore） */
    @PostMapping("/moderation/action")
    public Map<String, Object> moderationAction(@RequestParam int targetType,
                                                @RequestParam Long targetId,
                                                @RequestParam String action) {
        String table = targetType == 1 ? "posts" : "comments";
        int status = "hide".equals(action) ? 0 : 1;
        int rows = jdbcTemplate.update("UPDATE " + table + " SET status = ? WHERE id = ?", status, targetId);
        Map<String, Object> r = new HashMap<>();
        r.put("success", rows > 0);
        r.put("message", rows > 0 ? ("hide".equals(action) ? "已隐藏" : "已恢复") : "内容不存在");
        return r;
    }

    // ==================== 业务看板（阶段三） ====================

    /** 业务统计：用户/行程/DAU/热门目的地/论坛活跃 */
    @GetMapping("/stats/business")
    public Map<String, Object> businessStats() {
        Map<String, Object> r = new HashMap<>();
        r.put("totalUsers", qInt("SELECT COUNT(*) FROM users"));
        r.put("totalPlans", qInt("SELECT COUNT(*) FROM trip_plans"));
        r.put("todayPlans", qInt("SELECT COUNT(*) FROM trip_plans WHERE DATE(create_time) = CURDATE()"));
        r.put("totalPosts", qInt("SELECT COUNT(*) FROM posts WHERE status = 1"));
        r.put("todayPosts", qInt("SELECT COUNT(*) FROM posts WHERE DATE(created_at) = CURDATE()"));
        r.put("dau", qInt("SELECT COUNT(DISTINCT user_id) FROM (" +
                "SELECT user_id FROM trip_plans WHERE DATE(create_time) = CURDATE() " +
                "UNION SELECT user_id FROM posts WHERE DATE(created_at) = CURDATE()) t"));
        r.put("hotDestinations", jdbcTemplate.queryForList(
                "SELECT city AS destination, COUNT(*) AS cnt FROM trip_plans " +
                "WHERE city IS NOT NULL AND city != '' " +
                "GROUP BY city ORDER BY cnt DESC LIMIT 10"));
        r.put("success", true);
        return r;
    }

    private int qInt(String sql) {
        Integer v = jdbcTemplate.queryForObject(sql, Integer.class);
        return v == null ? 0 : v;
    }

    // ==================== 公告发布（阶段三） ====================

    /** 公告列表 */
    @GetMapping("/announcements")
    public Map<String, Object> announcements() {
        Map<String, Object> r = new HashMap<>();
        r.put("announcements", jdbcTemplate.queryForList(
                "SELECT a.id, a.title, a.content, a.created_by, u.username, a.created_at " +
                "FROM announcements a LEFT JOIN users u ON u.id = a.created_by ORDER BY a.created_at DESC LIMIT 50"));
        r.put("success", true);
        return r;
    }

    /** 发布公告 → 全员通知（type=5） + SSE 实时推送 */
    @PostMapping("/announcements")
    public Map<String, Object> createAnnouncement(@RequestParam String title,
                                                  @RequestParam String content,
                                                  HttpSession session) {
        long adminId = ((Number) session.getAttribute("userId")).longValue();
        Map<String, Object> r = new HashMap<>();
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            r.put("success", false);
            r.put("message", "标题和内容不能为空");
            return r;
        }
        jdbcTemplate.update(
                "INSERT INTO announcements(title, content, created_by) VALUES (?, ?, ?)",
                title.trim(), content, adminId);
        // 全员推送：所有正常用户各发一条通知 + SSE 实时推
        List<Long> userIds = jdbcTemplate.queryForList("SELECT id FROM users WHERE status = 1", Long.class);
        for (Long uid : userIds) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO notifications(user_id, type, content, link_url) VALUES (?, 5, ?, NULL)",
                        uid, title.trim());
                NotificationHub.push(uid, "{\"type\":5,\"content\":\"" +
                        title.trim().replace("\"", "\\\"") + "\",\"link\":\"\"}");
            } catch (Exception ignored) { /* 单条失败不影响整体 */ }
        }
        r.put("success", true);
        r.put("message", "公告已发布并推送给 " + userIds.size() + " 位用户");
        return r;
    }
// ==================== 数据中心 ====================

    /** 表存在性容错查询：表不存在返回空列表 */
    private java.util.List<Map<String, Object>> qRowsSafe(String sql) {
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    /**
     * 数据统计明细（type=attractions/foods/hotels/cities/profiles）。
     * 返回 {success, title, columns:[...], rows:[[...],...]}，前端弹窗渲染表格。
     */
    @GetMapping("/data-detail")
    public Map<String, Object> dataDetail(@RequestParam String type) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        try {
            List<Map<String, Object>> rows;
            List<String> columns;
            String title;
            switch (type) {
                case "attractions":
                    title = "景点数据明细";
                    columns = List.of("名称", "城市", "评分", "参考费用", "地址");
                    // 参考费用优先取特征表真实票价（高德enrich），无则退主表cost
                    rows = jdbcTemplate.queryForList(
                            "SELECT a.id AS id, a.name AS name, a.city AS city, a.rating AS rating, "
                          + "COALESCE(NULLIF(af.ticket_price, ''), NULLIF(a.cost, '')) AS cost, a.address AS address "
                          + "FROM attractions a LEFT JOIN attractions_features af ON af.src_id = a.id "
                          + "ORDER BY a.city, a.rating DESC LIMIT 500");
                    break;
                case "foods":
                    title = "美食数据明细";
                    columns = List.of("名称", "城市", "评分", "人均", "地址");
                    rows = jdbcTemplate.queryForList(
                            "SELECT id, name, city, rating, cost, address FROM foods ORDER BY city, rating DESC LIMIT 500");
                    break;
                case "hotels":
                    title = "酒店数据明细";
                    columns = List.of("名称", "城市", "评分", "参考价", "地址");
                    // 参考价取特征表cost（高德biz_ext，仅少量有值），其余显示"-"（OTA无公开房价源）
                    rows = jdbcTemplate.queryForList(
                            "SELECT h.id AS id, h.name AS name, h.city AS city, h.rating AS rating, "
                          + "COALESCE(NULLIF(hf.cost, ''), NULLIF(h.cost, '')) AS cost, h.address AS address "
                          + "FROM hotels h LEFT JOIN hotels_features hf ON hf.src_id = h.id "
                          + "ORDER BY h.city, h.rating DESC LIMIT 500");
                    break;
                case "cities":
                    title = "覆盖城市明细";
                    columns = List.of("城市", "景点数", "美食数", "酒店数");
                    rows = jdbcTemplate.queryForList(
                            "SELECT a.city AS city, "
                          + "(SELECT COUNT(*) FROM attractions x WHERE x.city=a.city) AS attractions, "
                          + "(SELECT COUNT(*) FROM foods f WHERE f.city=a.city) AS foods, "
                          + "(SELECT COUNT(*) FROM hotels h WHERE h.city=a.city) AS hotels "
                          + "FROM (SELECT DISTINCT city FROM attractions) a ORDER BY a.city LIMIT 200");
                    break;
                case "profiles":
                    title = "用户画像明细";
                    columns = List.of("用户名", "用户ID", "画像标签(JSON)", "更新时间");
                    rows = jdbcTemplate.queryForList(
                            "SELECT IFNULL(u.username,'-') AS username, t.user_id, t.tags, t.updated_at " +
                            "FROM user_profiles_tags t LEFT JOIN users u ON u.id = t.user_id ORDER BY t.updated_at DESC LIMIT 200");
                    break;
                default:
                    r.put("error", "未知类型: " + type);
                    return r;
            }
            r.put("success", true);
            r.put("title", title);
            // 行转对象数组，前端按 key 显示（keySet 无序不可靠）
            List<List<Object>> aligned = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                List<Object> line = new ArrayList<>();
                // 首格=主键id（前端编辑用），columns 展示时跳过
                line.add(row.get("id") == null ? "-" : row.get("id").toString());
                for (String col : columns) {
                    Object v = row.get(colKey(type, col));
                    line.add(v == null ? "-" : v.toString());
                }
                aligned.add(line);
            }
            r.put("columns", columns);
            // 画像明细：标签JSON转"标签×次数"串，时间只留秒级
            if ("profiles".equals(type)) {
                List<List<Object>> pretty = new ArrayList<>();
                for (List<Object> line : aligned) {
                    List<Object> out = new ArrayList<>(line); // [用户名, 用户ID, tagsJSON, updatedAt]
                    if (out.size() >= 4) {
                        // tags 保留原始JSON（前端标签云渲染），只去掉时间毫秒
                        out.set(3, String.valueOf(out.get(3)).replaceAll("\\.\\d+$", ""));
                    }
                    pretty.add(out);
                }
                r.put("rows", pretty);
            } else {
                r.put("rows", aligned);
            }
        } catch (Exception e) {
            r.put("error", e.getMessage());
        }
        return r;
    }

    /** 明细列名 → SQL 返回 key 映射（中文列头对应数据库字段） */
    private String colKey(String type, String col) {
        switch (col) {
            case "名称": return "name";
            case "城市": return "city";
            case "评分": return "rating";
            case "参考费用": return "cost";
            case "人均": return "cost";
            case "参考价": return "cost";
            case "地址": return "address";
            case "景点数": return "attractions";
            case "美食数": return "foods";
            case "酒店数": return "hotels";
            case "类型": return "action";
            case "用户名": return "username";
            case "用户ID": return "user_id";
            case "行程ID": return "plan_id";
            case "时间": return "created_at";
            case "画像标签(JSON)": return "tags";
            case "更新时间": return "updated_at";
            default: return col;
        }
    }

    /**
     * 手动编辑数据明细（景点/美食/酒店）。
     * type=attractions/foods/hotels，field=name/city/rating/cost/address；cost 写特征表，其余写主表。
     */
    @PostMapping("/data-detail/edit")
    public Map<String, Object> editDataDetail(@RequestParam String type, @RequestParam Long id,
                                              @RequestParam String field, @RequestParam String value) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        if (!java.util.Set.of("attractions", "foods", "hotels").contains(type)) {
            r.put("message", "类型不支持"); return r;
        }
        if (!java.util.Set.of("name", "city", "rating", "cost", "address").contains(field)) {
            r.put("message", "字段不支持: " + field); return r;
        }
        try {
            String val = (value == null ? "" : value.trim());
            if (field.equals("cost")) {
                int mainRows = jdbcTemplate.update(
                        "UPDATE " + tableName(type) + " SET cost = ? WHERE id = ?", val, id);
                if (mainRows == 0) {
                    r.put("message", "主记录不存在，未保存");
                    return r;
                }
                String featureSql = switch (type) {
                    case "attractions" -> "UPDATE attractions_features SET ticket_price = ? WHERE src_id = ?";
                    case "foods" -> "UPDATE foods_features SET cost = ? WHERE src_id = ?";
                    default -> "UPDATE hotels_features SET cost = ? WHERE src_id = ?";
                };
                int featureRows = jdbcTemplate.update(featureSql, val, id);
                if (featureRows == 0) {
                    r.put("message", "主记录已保存，但关联特征记录不存在；动态定价可能不会生效");
                    return r;
                }
            } else {
                String col = field.equals("name") ? "name" : field;
                int rows = jdbcTemplate.update(
                        "UPDATE " + tableName(type) + " SET " + col + " = ? WHERE id = ?", val, id);
                if (rows == 0) {
                    r.put("message", "记录不存在，未保存");
                    return r;
                }
            }
            r.put("success", true);
            r.put("message", "已保存");
        } catch (Exception e) {
            r.put("message", "保存失败: " + e.getMessage());
        }
        return r;
    }

    /** 数据统计手动编辑辅助：表名白名单 */
    private String tableName(String type) {
        return type.equals("attractions") ? "attractions" : type.equals("foods") ? "foods" : "hotels";
    }

    /** 定价专用刷新：只跑 pricing.py（秒级），不碰采集管线 */
    @PostMapping("/pricing/run")
    public Map<String, Object> runPricing() {
        Map<String, Object> r = new HashMap<>();
        try {
            java.io.File script = new java.io.File(projectRoot(), "data-pipeline/pricing.py");
            if (script == null) { r.put("success", false); r.put("message", "找不到 pricing.py"); return r; }
            ProcessBuilder pb = new ProcessBuilder(utils.Config.get("PYTHON_BIN", "python"), script.getCanonicalPath());
            pb.directory(new java.io.File(script.getCanonicalPath()).getParentFile().getParentFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); r.put("success", false); r.put("message", "定价更新超时"); return r; }
            r.put("success", p.exitValue() == 0);
            r.put("message", p.exitValue() == 0 ? "定价已更新" : "定价脚本退出码 " + p.exitValue());
        } catch (Exception e) {
            r.put("success", false);
            r.put("message", "执行失败: " + e.getMessage());
        }
        return r;
    }

    /** 数据管线统计：各表行数（表不存在返回0） */
    @GetMapping("/data-stats")
    public Map<String, Object> dataStats() {
        Map<String, Object> r = new HashMap<>();
        r.put("attractions", qIntSafe("SELECT COUNT(*) FROM attractions"));
        r.put("foods", qIntSafe("SELECT COUNT(*) FROM foods"));
        r.put("hotels", qIntSafe("SELECT COUNT(*) FROM hotels"));
        r.put("cities", qIntSafe("SELECT COUNT(DISTINCT city) FROM attractions"));
        r.put("profiles", qIntSafe("SELECT COUNT(*) FROM user_profiles_tags"));
        r.put("success", true);
        return r;
    }

    private int qIntSafe(String sql) {
        try {
            Integer v = jdbcTemplate.queryForObject(sql, Integer.class);
            return v == null ? 0 : v;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 动态定价结果 */
    @GetMapping("/pricing")
    public Map<String, Object> pricing() {
        Map<String, Object> r = new HashMap<>();
        r.put("rows", qRowsSafe("SELECT city, hotel_base, hotel_price, food_base, food_price, ticket_base, ticket_price, factors FROM pricing_output ORDER BY hotel_price DESC"));
        r.put("success", true);
        return r;
    }

    /** 客流预测（未来7天） */
    @GetMapping("/forecast")
    public Map<String, Object> forecast() {
        Map<String, Object> r = new HashMap<>();
        r.put("rows", qRowsSafe("SELECT city, day, forecast_idx FROM forecast_output ORDER BY day, city"));
        r.put("success", true);
        return r;
    }

    /** 用户画像 */
    @GetMapping("/profiles")
    public Map<String, Object> profiles() {
        Map<String, Object> r = new HashMap<>();
        r.put("profiles", qRowsSafe("SELECT t.user_id, u.username, t.tags FROM user_profiles_tags t LEFT JOIN users u ON u.id = t.user_id"));
        r.put("success", true);
        return r;
    }
    /** 数据管线一键更新（异步执行 update_all.py，立即返回任务ID） */
    private final java.util.Map<String, String> PIPELINE_JOBS = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService PIPELINE_POOL = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile Process PIPELINE_PROC; // 当前更新子进程

    @PostMapping("/pipeline/run")
    public Map<String, Object> runPipeline() {
        Map<String, Object> r = new HashMap<>();
        for (String s : PIPELINE_JOBS.values()) {
            if ("running".equals(s)) {
                r.put("success", false);
                r.put("message", "已有更新任务在执行中，请稍候");
                return r;
            }
        }
        String jobId = "pipe_" + System.currentTimeMillis();
        PIPELINE_JOBS.put(jobId, "running");
        java.io.File script = new java.io.File(projectRoot(), "data-pipeline/update_all.py");
        final String scriptPath;
        try {
            scriptPath = script.getCanonicalPath();
        } catch (Exception e) {
            PIPELINE_JOBS.remove(jobId);
            r.put("success", false); r.put("message", "脚本路径错误");
            return r;
        }
        PIPELINE_POOL.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(utils.Config.get("PYTHON_BIN", "python"), scriptPath);
                pb.directory(new java.io.File(scriptPath).getParentFile().getParentFile());
                pb.redirectErrorStream(true);
                pb.redirectOutput(new java.io.File(System.getProperty("java.io.tmpdir"), "atp_pipeline_" + jobId + ".log"));
                Process p = pb.start();
                PIPELINE_PROC = p;
                p.waitFor();
                PIPELINE_JOBS.put(jobId, p.exitValue() == 0 ? "done" : "stopped_or_failed(exit=" + p.exitValue() + ")");
            } catch (Exception e) {
                PIPELINE_JOBS.put(jobId, "error: " + e.getMessage());
            }
        });
        r.put("success", true);
        r.put("jobId", jobId);
        r.put("message", "数据更新已在后台启动（采集→预处理→高德v5→等级→LLM，约30分钟）");
        return r;
    }

    /** 查询更新任务状态 */
    @GetMapping("/pipeline/status")
    public Map<String, Object> pipelineStatus() {
        Map<String, Object> r = new HashMap<>();
        r.put("jobs", PIPELINE_JOBS);
        r.put("running", PIPELINE_JOBS.values().stream().anyMatch("running"::equals));
        return r;
    }

    /** 停止正在执行的数据更新任务（杀掉 python 子进程树） */
    @PostMapping("/pipeline/stop")
    public Map<String, Object> stopPipeline() {
        Map<String, Object> r = new HashMap<>();
        Process p = PIPELINE_PROC;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(java.lang.ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            r.put("success", true);
            r.put("message", "已停止更新任务");
            return r;
        }
        boolean hasRunning = PIPELINE_JOBS.values().stream().anyMatch("running"::equals);
        r.put("success", hasRunning);
        r.put("message", hasRunning ? "已发送停止请求" : "当前没有正在执行的更新任务");
        return r;
    }
}
