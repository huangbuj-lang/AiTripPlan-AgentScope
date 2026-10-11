package managerAgent.service;

import managerAgent.tool.HttpProbe;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import org.springframework.web.bind.annotation.*;
import utils.CircuitBreaker;
import utils.NacosUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** AdminSystem 的业务实现（自原控制器等价平移，逻辑未改）。 */
@Service
public class AdminSystemService {

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
        nacos.put("addr", utils.AgentEndpoints.nacosAddr()); // ★2026-09-11 端点集中配置
        nacos.put("connected", nacosOk);
        nacos.put("error", nacosErr);
        r.put("nacos", nacos);

        List<Map<String, Object>> agents = new ArrayList<>();
        agents.add(agentStatus("RouteMakingAgent", utils.AgentEndpoints.routePort(), "路线"));
        agents.add(agentStatus("TripPlannerAgent", utils.AgentEndpoints.tripPort(), "行程"));
        r.put("agents", agents);

        r.put("success", true);
        return r;
    }

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
        //total 先=主管内存值
        total.put("calls", totCalls);
        total.put("inputTokens", totIn);
        total.put("outputTokens", totOut);
        r.put("total", total);
        //成本落库：把内存累计值 upsert 到当日维度表
        try {
            for (var e : utils.SafeModel.dimensionStatsSnapshot().entrySet()) {
                long[] st = e.getValue();
                utils.TracePersistence.upsertLlmCost(e.getKey(), st[0], st[1], st[2]);
            }
        } catch (Exception ignored) { }
        r.put("trend", utils.TracePersistence.costTrend(7)); // 近7日按天趋势
        //子Agent成本聚合：探活 /stats 拉 tokens 并进 total 与 dimensions
        for (String[] agent : new String[][]{{"RouteMakingAgent", "路线Agent", String.valueOf(utils.AgentEndpoints.routePort())}, {"TripPlannerAgent", "行程Agent", String.valueOf(utils.AgentEndpoints.tripPort())}}) {
            try {
                java.net.URL u = new java.net.URL(utils.AgentEndpoints.forAgentLabel(agent[0]) + "/stats");
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
        //缓存监控回显：命中/未中/命中率/存量（PlanCache 内存计数，重启清零）
        try {
            r.put("cache", utils.cache.PlanCache.stats());
        } catch (Exception ignored) {
            r.put("cache", Map.of("hits", 0L, "misses", 0L, "hitRate", 0.0, "size", 0));
        }
        r.put("success", true);
        return r;
    }

    public Map<String, Object> agentsHealth() {
        Map<String, Object> r = new HashMap<>();
        r.put("agents", java.util.List.of(
                managerHealth(),
                agentHealth("RouteMakingAgent", "路线Agent", utils.AgentEndpoints.routePort()),
                agentHealth("TripPlannerAgent", "行程Agent", utils.AgentEndpoints.tripPort())));
        r.put("success", true);
        return r;
    }

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
        //A2A 字段默认值（主管不发起 A2A 调用）：与子Agent卡片一致显示 0/0/-
        m.put("a2aCalls", 0L);
        m.put("a2aFails", 0L);
        m.put("a2aSuccessRate", -1.0);
        m.put("a2aAvgCostMs", -1L);
        m.put("light", utils.CircuitBreaker.getInstance().isOpen() ? "yellow" : "green");
        return m;
    }

    private Map<String, Object> agentHealth(String name, String label, int port) {
        Map<String, Object> a = new HashMap<>();
        a.put("name", name);
        a.put("label", label);
        a.put("port", port);
        //1. 探活：HTTP GET /stats，2s 连接/读取超时即视为挂
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
        //2. 主管侧 A2A 调用统计（RemoteAgentTool 埋点累计）
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
        //综合灯色：按存活与成功率分绿/黄/红/灰四级
        String light;
        if (!alive) light = "red";
        else if (calls == 0) light = "gray";
        else light = (calls - fails) * 100 >= calls * 90 ? "green" : "yellow";
        a.put("light", light);
        return a;
    }

    public Map<String, Object> tools() {
        Map<String, Object> r = new HashMap<>();
        java.util.List<Map<String, Object>> agents = new ArrayList<>();
        for (String[] agent : new String[][]{{"RouteMakingAgent", "路线Agent", String.valueOf(utils.AgentEndpoints.routePort())}, {"TripPlannerAgent", "行程Agent", String.valueOf(utils.AgentEndpoints.tripPort())}}) {
            Map<String, Object> a = new HashMap<>();
            a.put("name", agent[0]);
            a.put("label", agent[1]);
            try {
                String body = HttpProbe.getBody(utils.AgentEndpoints.forAgentLabel(agent[0]) + "/stats", 2000);
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

    private static long numOf(Object o) {
        return o instanceof Number n ? n.longValue() : -1;
    }

    public Map<String, Object> breaker() {
        CircuitBreaker cb = CircuitBreaker.getInstance();
        Map<String, Object> r = new HashMap<>();
        r.put("state", cb.getState().name());
        r.put("consecutiveFailures", cb.getConsecutiveFailures());
        r.put("primaryModel", utils.ModelConfigHolder.modelName());
        r.put("fallbackModel", utils.ModelConfigHolder.fbModelName());
        r.put("maxQps", utils.SafeModel.getMaxQps());
        r.put("maxInflight", utils.SafeModel.getMaxInflight()); // 在途并发上限
        r.put("inflightActive", utils.SafeModel.getInflightActive()); // 当前在途数
        r.put("maxRetries", utils.SafeModel.getMaxRetries());
        r.put("retryBaseMs", utils.SafeModel.getRetryBaseMs());
        r.put("breakerFailureThreshold", utils.CircuitBreaker.getFailureThreshold());
        r.put("breakerWindowMs", utils.CircuitBreaker.getWindowMs());
        r.put("breakerCoolDownMs", utils.CircuitBreaker.getCoolDownMs());
        r.put("success", true);
        return r;
    }
}
