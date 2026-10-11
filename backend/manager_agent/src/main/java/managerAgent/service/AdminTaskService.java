package managerAgent.service;

import managerAgent.service.TripTaskService;
import managerAgent.tool.HttpProbe;
import managerAgent.hook.EventStore;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** AdminTask 的业务实现（自原控制器等价平移，逻辑未改）。 */
@Service
public class AdminTaskService {

    public Map<String, Object> tasks() {
        Map<String, Object> r = new HashMap<>();
        r.put("maxConcurrent", TripTaskService.maxConcurrent());
        r.put("inUse", TripTaskService.inUseSlots());
        r.put("sessions", EventStore.listSessions());
        //任务监控扩展：结果计数（本次进程运行以来）
        r.put("countDone", EventStore.COUNT_DONE.get());
        r.put("countFailed", EventStore.COUNT_FAILED.get());
        r.put("countCancelled", EventStore.COUNT_CANCELLED.get());
        long total = EventStore.COUNT_DONE.get() + EventStore.COUNT_FAILED.get() + EventStore.COUNT_CANCELLED.get();
        r.put("failRate", total > 0 ? Math.round(EventStore.COUNT_FAILED.get() * 1000.0 / total) / 10.0 : 0);
        //历史任务列表：内存(近期) + DB(落库历史) 合并去重，新的在前最多200条
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
        //正在运行任务的当前步骤
        java.util.List<Map<String, Object>> runningList = new ArrayList<>();
        for (Map<String, String> sessionInfo : listRunningSteps()) {
            runningList.add(new HashMap<>(sessionInfo));
        }
        r.put("runningSteps", runningList);
        r.put("success", true);
        return r;
    }

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

    public Map<String, Object> cancelTask(String sessionId) {
        EventStore.requestCancel(sessionId);
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("message", "已向会话 " + sessionId + " 发送中断请求");
        return r;
    }

    public Map<String, Object> taskTrace(String sessionId) {
        Map<String, Object> r = new HashMap<>();
        r.put("sessionId", sessionId);
        java.util.List<Map<String, Object>> spans = new java.util.ArrayList<>(utils.CallTrace.traceAsMaps(sessionId));
        boolean fromDb = false;
        if (spans.isEmpty()) { // 内存无（进程重启后）：从库恢复历史 span
            spans = utils.TracePersistence.loadSpans(sessionId);
            if (!spans.isEmpty()) fromDb = true;
        }
        //任务开始时间：过滤掉子Agent进程里同 sessionId 上次执行残留的旧 span
        long taskStartMs = managerAgent.hook.EventStore.taskStartMs(sessionId);
        if (!fromDb) { // 库中已有完整合并结果，不再实时拉子Agent
        for (String[] agent : new String[][]{{"路线", String.valueOf(utils.AgentEndpoints.routePort())}, {"行程", String.valueOf(utils.AgentEndpoints.tripPort())}}) {
            try {
                String json = HttpProbe.getBody(utils.AgentEndpoints.forAgentLabel(agent[0]) + "/trace?sessionId="
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
        //只保留关键层：LLM/A2A/TOOL（MCP 底层每轮 ReAct 都调、量大刷屏，且成败已反映在外层 TOOL 行）
        spans.removeIf(s -> !"LLM".equals(s.get("layer")) && !"A2A".equals(s.get("layer")) && !"TOOL".equals(s.get("layer")));
        spans.sort((a, b) -> Long.compare(
                ((Number) a.getOrDefault("ts", 0L)).longValue(),
                ((Number) b.getOrDefault("ts", 0L)).longValue()));
        r.put("spans", spans);
        r.put("success", true);
        return r;
    }
}
