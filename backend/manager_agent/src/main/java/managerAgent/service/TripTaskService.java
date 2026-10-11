package managerAgent.service;

import dto.TripPlanData;
import dto.TripRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import managerAgent.hook.CancelledException;
import managerAgent.hook.EventStore;
import managerAgent.orchestrator.TripOrchestrator;
import managerAgent.tool.ConcurrencyGate;
import org.springframework.stereotype.Service;

import java.util.Map;
import utils.JsonUtils;

/**
 * 行程任务的提交与观测业务：并发闸门、后台编排执行、结果读取、中断、终态落库。
 * AppController 只做 HTTP 适配，这里不含任何请求/响应处理。
 */
@Slf4j
@Service
public class TripTaskService {

    @Resource
    private ProfileService profileService;

    /** 任务池：core=0 / max=无上限 / SynchronousQueue（容量 0 的交接队列，不缓冲）。
     *  并发上限由 TASK_GATE 在 submit 前把关（默认 1，管理端/Nacos 可调）；不设拒绝策略，
     *  否则长任务会被甩回 HTTP 线程导致请求超时。PARALLEL_POOL 负责任务内部的并行子步骤。 */
    private static final java.util.concurrent.ExecutorService TASK_POOL =
            new java.util.concurrent.ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L,
                    java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());

    //并发闸门：支持 Nacos 运行时调整，非法值不生效
    private static final ConcurrencyGate TASK_GATE = new ConcurrencyGate(initialMax());

    /** 并发上限（管理端任务监控展示） */
    public static int maxConcurrent() {
        syncConcurrency();
        return TASK_GATE.max();
    }

    /** 当前占用的并发槽数（管理端任务监控展示） */
    public static int inUseSlots() {
        return TASK_GATE.active();
    }

    /** 提交任务给主管Agent异步处理，返回给前端的提示语 */
    public String submit(String sessionId, String prompt, String multiScheme, boolean skipPrefs, Long userId) {
        //获取并发槽位，失败则拒绝
        syncConcurrency();
        if (!TASK_GATE.tryAcquire()) {
            return "⚠️ 有任务正在执行中（最多同时 " + TASK_GATE.max() + " 个），请等当前任务完成后再试！";
        }
        //绑定会话归属：防止他人凭 sessionId 越权读取/取消本用户任务
        if (userId != null) {
            EventStore.bindOwner(sessionId, userId);
        }
        EventStore.clear(sessionId); // 清空该会话的旧事件
        utils.CallTrace.clear(sessionId); // 清空调用链旧span，防重复提交时新旧混染
        EventStore.startTask(sessionId, userId == null ? -1 : userId); // 任务监控：登记开始

        TASK_POOL.submit(() -> {
            String taskStatus = "DONE"; // 任务最终状态（异常路径改写）
            try {
                TripOrchestrator orchestrator = new TripOrchestrator(sessionId); // 确定性编排器（会话隔离）
                //用户画像注入：与用户输入放一起
                String profileLine = (userId != null && !skipPrefs) ? profileService.profileLineForPlanning(userId) : "";
                String finalPrompt = profileLine + prompt;
                if ("true".equalsIgnoreCase(multiScheme)) { // 多方案模式
                    dto.PlanSet planSet = orchestrator.runMultiScheme(finalPrompt);
                    EventStore.setResult(sessionId, JsonUtils.toJson(planSet)); // 缓存多方案 JSON
                } else {
                    TripPlanData plan;
                    if (userId != null && !skipPrefs) { // 注入个人中心沉淀偏好
                        TripRequest.Preferences prefs = profileService.preferencesForPlanning(userId);
                        plan = orchestrator.run(finalPrompt, prefs);
                    } else {
                        plan = orchestrator.run(finalPrompt); // 解析->偏好分类->并行DAG->聚合
                    }
                    EventStore.setResult(sessionId, JsonUtils.toJson(plan)); // 缓存结构化 JSON 结果（含降级 markdown 字段）
                }
            } catch (CancelledException e) {
                taskStatus = "CANCELLED";
                EventStore.setResult(sessionId, "⏹ 任务已由用户中断。"); // 用户取消的友好提示（类型化判断）
            } catch (Exception e) {
                taskStatus = "FAILED";
                EventStore.setResult(sessionId, "❌ 任务执行出错：" + e.getMessage()); // 缓存错误信息
                log.error("任务执行失败: {}", e.getMessage(), e);
            } finally {
                TASK_GATE.release(); // 释放并发槽位
                EventStore.TaskMeta doneMeta = EventStore.finishTask(sessionId, taskStatus); // 登记结束并计数
                persistObservation(sessionId, taskStatus, userId, doneMeta); // 观测落库：任务历史 + 调用链span
            }
        });
        return "✅ 任务已接收，正在后台执行中！请查看思考过程，完成后结果自动显示。";
    }

    /** 获取任务最终结果（未完成时返回提示）（按会话隔离） */
    public String result(String sessionId, Long userId) {
        if (!EventStore.isKnown(sessionId)) {
            return "❌ 任务不存在或已失效，请重新提交"; // 服务重启后旧任务丢失：让前端清理恢复状态
        }
        if (userId == null || !EventStore.isOwner(sessionId, userId)) {
            return "⏳ 任务还在执行中..."; // 非本人会话：不泄露他人结果
        }
        String r = EventStore.getResult(sessionId);
        return r == null ? "⏳ 任务还在执行中..." : r;
    }

    /** 中断当前任务 */
    public String cancel(String sessionId, Long userId) {
        if (userId == null || !EventStore.isOwner(sessionId, userId)) {
            return "❌ 无权操作该任务";
        }
        EventStore.requestCancel(sessionId);
        return "✅ 已发送中断请求，任务将在下一步停止。";
    }

    /** 观测落库（任务终态）：task_history upsert + 调用链span合并三进程落库（异步线程，失败不影响主流程） */
    private static void persistObservation(String sessionId, String taskStatus, Long userId,
                                           EventStore.TaskMeta meta) {
        try {
            long uid = userId == null ? -1 : userId;
            if (meta == null) return;
            int modelCalls = meta.modelCallsUsed > 0 ? (int) meta.modelCallsUsed : 0;
            utils.TracePersistence.upsertTask(sessionId, uid, taskStatus, meta.currentStep,
                    meta.submitMs, meta.lastActiveMs, modelCalls, "");
            //成本落库：任务终态把主管内存累计+两子Agent tokens 写当日维度表
            try {
                for (var e : utils.SafeModel.dimensionStatsSnapshot().entrySet()) {
                    long[] st = e.getValue();
                    utils.TracePersistence.upsertLlmCost(e.getKey(), st[0], st[1], st[2]);
                }
                for (String[] agent : new String[][]{{"行程Agent", String.valueOf(utils.AgentEndpoints.tripPort())}, {"路线Agent", String.valueOf(utils.AgentEndpoints.routePort())}}) { // 子Agent维度
                    try {
                        java.net.URL u = new java.net.URL(utils.AgentEndpoints.forAgentLabel(agent[0]) + "/stats");
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                        conn.setConnectTimeout(1500); conn.setReadTimeout(1500);
                        if (conn.getResponseCode() == 200) {
                            Map<?, ?> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(conn.getInputStream(), Map.class);
                            long mc = json.get("model_calls") instanceof Number n ? n.longValue() : 0;
                            long ti = json.get("tokens_in") instanceof Number n ? n.longValue() : 0;
                            long to = json.get("tokens_out") instanceof Number n ? n.longValue() : 0;
                            if (mc > 0) utils.TracePersistence.upsertLlmCost(agent[0], mc, ti, to);
                        }
                        conn.disconnect();
                    } catch (Exception ignore) { }
                }
            } catch (Exception ignored) { }
            //调用链 span：合并主管(内存) + 两子Agent(/trace)，终态后一次性落库
            java.util.List<Map<String, Object>> spans = new java.util.ArrayList<>(
                    utils.CallTrace.traceAsMaps(sessionId));
            for (String[] agent : new String[][]{{"路线", String.valueOf(utils.AgentEndpoints.routePort())}, {"行程", String.valueOf(utils.AgentEndpoints.tripPort())}}) {
                try {
                    java.net.URL u = new java.net.URL(utils.AgentEndpoints.forAgentLabel(agent[0]) + "/trace?sessionId="
                            + java.net.URLEncoder.encode(sessionId, java.nio.charset.StandardCharsets.UTF_8));
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(2000); conn.setReadTimeout(2000);
                    if (conn.getResponseCode() == 200) {
                        Map<?, ?> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(conn.getInputStream(), Map.class);
                        if (parsed.get("spans") instanceof java.util.List<?> list) {
                            for (Object o : list) {
                                if (o instanceof Map<?, ?> m) {
                                    Map<String, Object> mm = new java.util.HashMap<>(m.size() + 1);
                                    mm.putAll((Map<String, Object>) m);
                                    mm.put("agent", agent[0]);
                                    spans.add(mm);
                                }
                            }
                        }
                    }
                    conn.disconnect();
                } catch (Exception ignored) { }
            }
            spans.sort((a, b) -> Long.compare(
                    ((Number) a.getOrDefault("ts", 0L)).longValue(),
                    ((Number) b.getOrDefault("ts", 0L)).longValue()));
            utils.TracePersistence.saveSpans(sessionId, spans);
        } catch (Exception e) {
            System.err.println("[persistObservation] 落库失败(忽略): " + e.getMessage());
        }
    }

    private static int initialMax() {
        try {
            return Integer.parseInt(System.getenv().getOrDefault("MAX_CONCURRENT_TASKS", "1").trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static void syncConcurrency() {
        java.util.Map<String, Object> orchestration = utils.NacosModelConfig.orchestrationConfig();
        if (orchestration == null) {
            return;
        }
        if (orchestration.get("maxConcurrentTasks") instanceof Number number) {
            TASK_GATE.setMax(number.intValue());
        } else {
            TASK_GATE.setMax(initialMax());
        }
    }
}
