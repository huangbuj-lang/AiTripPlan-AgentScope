package managerAgent.controller;

import dto.TripPlanData;
import dto.TripRequest;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import managerAgent.hook.CancelledException;
import managerAgent.hook.EventStore;
import managerAgent.orchestrator.TripOrchestrator;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utils.JsonUtils;
import utils.SafeModel;
import utils.ExternalServices;

/**
 * 用户与Agent互动的HTTP接口
 */
@Slf4j
@RestController
public class AppController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    // 异步任务线程池：并发数量由 TASK_GATE 控制，线程空闲后自动回收
    private static final java.util.concurrent.ExecutorService TASK_POOL =
            new java.util.concurrent.ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L,
                    java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());

    // 并发闸门：支持 Nacos 运行时调整，非法值不生效
    private static final ConcurrencyGate TASK_GATE = new ConcurrencyGate(initialMax());

    /** 并发上限（管理端任务监控展示） */
    public static int maxConcurrent() {
        syncConcurrency();
        return TASK_GATE.max();
    }

    /** 当前占用的并发槽数（管理端任务监控展示） */
    public static int inUseSlots() { return TASK_GATE.active(); }

    /**
     * HTTP入口：提交任务给主管Agent异步处理
     */
    @RequestMapping(value = "/app", method = RequestMethod.POST)
    public String app(@RequestParam(name = "prompt", required = false, defaultValue = "帮我制定2026年元旦,深圳到惠州3日游自驾游计划，请包含吃住行，天气，酒店，餐饮美食。") String prompt,
                      @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,
                      @RequestParam(name = "multiScheme", required = false, defaultValue = "false") String multiScheme,
                      @RequestParam(name = "skipPrefs", required = false, defaultValue = "false") boolean skipPrefs,
                      HttpSession session) {

        // 获取并发槽位，失败则拒绝
        syncConcurrency();
        if (!TASK_GATE.tryAcquire()) {
            return "⚠️ 有任务正在执行中（最多同时 " + TASK_GATE.max() + " 个），请等当前任务完成后再试！";
        }

        // 绑定会话归属：防止他人凭 sessionId 越权读取/取消本用户任务
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj != null) {
            EventStore.bindOwner(sessionId, ((Number) userIdObj).longValue());
        }

        EventStore.clear(sessionId); // 清空该会话的旧事件
        utils.CallTrace.clear(sessionId); // ★清空调用链旧span，防重复提交时新旧混染
        EventStore.startTask(sessionId, userIdObj instanceof Number n ? n.longValue() : -1); // 任务监控：登记开始

        TASK_POOL.submit(() -> {
            String taskStatus = "DONE"; // 任务最终状态（异常路径改写）
            try {
                // 用户输入事件：前端思考区首条（截断防超长 prompt 撑爆事件流）
                String promptPreview = prompt == null ? "" : (prompt.length() > 80 ? prompt.substring(0, 80) + "…" : prompt);
                EventStore.add(sessionId, "用户输入", promptPreview);
                TripOrchestrator orchestrator = new TripOrchestrator(sessionId); // 确定性编排器（会话隔离）
                // 用户画像注入：与用户输入放一起
                String profileLine = (userIdObj != null && !skipPrefs)
                        ? loadProfileTags(((Number) userIdObj).longValue()) : "";
                String finalPrompt = profileLine + prompt;
                if ("true".equalsIgnoreCase(multiScheme)) { // 多方案模式
                    dto.PlanSet planSet = orchestrator.runMultiScheme(finalPrompt);
                    EventStore.setResult(sessionId, JsonUtils.toJson(planSet)); // 缓存多方案 JSON
                } else {
                    TripPlanData plan;
                    if (userIdObj != null && !skipPrefs) { // 注入个人中心沉淀偏好
                        TripRequest.Preferences prefs = loadPreferences(((Number) userIdObj).longValue());
                        plan = orchestrator.run(finalPrompt, prefs);
                    } else {
                        plan = orchestrator.run(finalPrompt); // 解析→偏好分类→并行DAG→聚合
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
                managerAgent.hook.EventStore.TaskMeta doneMeta = EventStore.finishTask(sessionId, taskStatus); // 登记结束并计数
                persistObservation(sessionId, taskStatus, userIdObj, doneMeta); // ★观测落库：任务历史 + 调用链span
            }
        });

        return "✅ 任务已接收，正在后台执行中！请查看思考过程，完成后结果自动显示。";
    }

    /**
     * 会话式增量修正入口（PDF §十三）：对当前会话最近一次生成的行程提出一句修改指令
     * （如"第三天不去稻城了，改去四姑娘山"），走影响分析 → 只重跑受影响域 → 局部重聚合 → 再校验。
     */
    @RequestMapping(value = "/app/revise", method = RequestMethod.POST)
    public String revise(@RequestParam(name = "instruction", required = true) String instruction,
                         @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,
                         HttpSession session) {
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null || !EventStore.isOwner(sessionId, ((Number) userIdObj).longValue())) {
            return "❌ 无权操作该会话任务";
        }
        // 读取上一版结果（必须在清空事件前）
        String prevRaw = EventStore.getResult(sessionId);
        dto.TripPlanData prev = parsePrevPlan(prevRaw);
        if (prev == null) {
            return "⚠️ 当前会话没有可修正的行程结果，请先生成一次行程。";
        }
        if (prev.getSections() == null || prev.getSections().isEmpty()) {
            return "⚠️ 上一版结果为降级文本（结构化数据缺失），暂不支持增量修正，请重新生成。";
        }
        final dto.TripPlanData prevPlan = prev;
        syncConcurrency();
        if (!TASK_GATE.tryAcquire()) {
            return "⚠️ 有任务正在执行中（最多同时 " + TASK_GATE.max() + " 个），请等当前任务完成后再试！";
        }
        EventStore.clear(sessionId);
        utils.CallTrace.clear(sessionId);
        EventStore.startTask(sessionId, userIdObj instanceof Number n ? n.longValue() : -1);

        TASK_POOL.submit(() -> {
            String taskStatus = "DONE";
            try {
                EventStore.add(sessionId, "用户输入", "（修订）" + instruction);
                managerAgent.orchestrator.PlanReviser reviser = new managerAgent.orchestrator.PlanReviser(sessionId);
                dto.TripPlanData revised = reviser.revise(instruction, prevPlan);
                if (revised == null) {
                    EventStore.add(sessionId, "修订", "修正未完成，已保留原方案");
                    EventStore.setResult(sessionId, JsonUtils.toJson(prevPlan)); // 保底回填原方案
                } else {
                    EventStore.setResult(sessionId, JsonUtils.toJson(revised));
                }
            } catch (CancelledException e) {
                taskStatus = "CANCELLED";
                EventStore.setResult(sessionId, "⏹ 修订任务已由用户中断。");
            } catch (Exception e) {
                taskStatus = "FAILED";
                EventStore.setResult(sessionId, "❌ 修订任务执行出错：" + e.getMessage());
                log.error("修订任务执行失败: {}", e.getMessage(), e);
            } finally {
                TASK_GATE.release();
                managerAgent.hook.EventStore.TaskMeta doneMeta = EventStore.finishTask(sessionId, taskStatus);
                persistObservation(sessionId, taskStatus, userIdObj, doneMeta);
            }
        });
        return "✅ 修订任务已接收，正在影响分析与局部重跑...";
    }

    /** 解析上一版结果为 TripPlanData（PlanSet 取推荐方案；旧 markdown/错误文本返回 null） */
    private dto.TripPlanData parsePrevPlan(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            java.util.Map<?, ?> node = JsonUtils.extractAndParse(raw, java.util.Map.class);
            if (node == null) return null;
            if (node.get("plans") instanceof java.util.List<?> plans && !plans.isEmpty()) {
                // 多方案：取推荐方案（缺推荐下标取第一个结构化的）
                int rec = node.get("recommendedIndex") instanceof Number n ? n.intValue() : -1;
                Object pick = (rec >= 0 && rec < plans.size()) ? plans.get(rec) : null;
                if (pick == null) {
                    for (Object o : plans) {
                        if (o instanceof java.util.Map<?, ?> m && m.get("sections") instanceof java.util.List<?> ss && !ss.isEmpty()) {
                            pick = o;
                            break;
                        }
                    }
                }
                if (pick == null) return null;
                return JsonUtils.extractAndParse(JsonUtils.toJson(pick), dto.TripPlanData.class);
            }
            if (node.get("sections") instanceof java.util.List<?> sections && !sections.isEmpty()) {
                return JsonUtils.extractAndParse(raw, dto.TripPlanData.class);
            }
            return null;
        } catch (Exception e) {
            log.warn("解析上一版结果失败: {}", e.getMessage());
            return null;
        }
    }

    /** ★观测落库（任务终态）：task_history upsert + 调用链span合并三进程落库（异步线程，失败不影响主流程） */
    private static void persistObservation(String sessionId, String taskStatus, Object userIdObj,
                                           managerAgent.hook.EventStore.TaskMeta meta) {
        try {
            long userId = userIdObj instanceof Number n ? n.longValue() : -1;
            if (meta == null) return;
            int modelCalls = meta.modelCallsUsed > 0 ? (int) meta.modelCallsUsed : 0;
            utils.TracePersistence.upsertTask(sessionId, userId, taskStatus, meta.currentStep,
                    meta.submitMs, meta.lastActiveMs, modelCalls, "");
            // ★成本落库：任务终态把主管内存累计+两子Agent tokens 写当日维度表
            try {
                for (var e : utils.SafeModel.dimensionStatsSnapshot().entrySet()) {
                    long[] st = e.getValue();
                    utils.TracePersistence.upsertLlmCost(e.getKey(), st[0], st[1], st[2]);
                }
                for (String[] agent : new String[][]{{"行程Agent", "8085"}, {"路线Agent", "8082"}}) { // 子Agent维度
                    try {
                        java.net.URL u = new java.net.URL("http://localhost:" + agent[1] + "/stats");
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
            // 调用链 span：合并主管(内存) + 两子Agent(/trace)，终态后一次性落库
            java.util.List<Map<String, Object>> spans = new java.util.ArrayList<>(
                    utils.CallTrace.traceAsMaps(sessionId));
            for (String[] agent : new String[][]{{"路线", "8082"}, {"行程", "8085"}}) {
                try {
                    java.net.URL u = new java.net.URL("http://localhost:" + agent[1] + "/trace?sessionId="
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
            // ★结构化观测落库（task_obs）：reviseRounds/reviseOutcome/clusterStats——进程重启后 events 质量观测从库恢复
            java.util.Map<String, Object> obs = new java.util.HashMap<>();
            Object rounds = managerAgent.hook.EventStore.getExtra(sessionId, "reviseRounds");
            Object outcome = managerAgent.hook.EventStore.getExtra(sessionId, "reviseOutcome");
            Object cluster = managerAgent.hook.EventStore.getExtra(sessionId, "clusterStats");
            if (rounds != null) obs.put("reviseRounds", rounds);
            if (outcome != null) obs.put("reviseOutcome", outcome);
            if (cluster != null) obs.put("clusterStats", cluster);
            utils.TracePersistence.saveObs(sessionId, obs);
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

    /**
     * 获取任务最终结果（未完成时返回提示）（按会话隔离）
     */
    @RequestMapping(value = "/result", method = RequestMethod.GET)
    public String result(@RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,
                         HttpSession session) {
        if (!EventStore.isKnown(sessionId)) {
            return "❌ 任务不存在或已失效，请重新提交"; // 服务重启后旧任务丢失：让前端清理恢复状态
        }
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null || !EventStore.isOwner(sessionId, ((Number) userIdObj).longValue())) {
            return "⏳ 任务还在执行中..."; // 非本人会话：不泄露他人结果
        }
        String r = EventStore.getResult(sessionId);
        return r == null ? "⏳ 任务还在执行中..." : r;
    }

    /**
     * 中断当前任务
     */
    @RequestMapping(value = "/cancel", method = RequestMethod.POST)
    public String cancel(@RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,
                         HttpSession session) {
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null || !EventStore.isOwner(sessionId, ((Number) userIdObj).longValue())) {
            return "❌ 无权操作该任务";
        }
        EventStore.requestCancel(sessionId);
        return "✅ 已发送中断请求，任务将在下一步停止。";
    }

    /**
     * 观测接口：返回本进程的模型调用次数（临时调试用）
     */
    @RequestMapping(value = "/stats", method = RequestMethod.GET)
    public java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("model_calls", SafeModel.MODEL_CALLS.get()); // 本进程模型调用次数（每次ReAct轮=1次）
        return m;
    }

    /**
     * 高德地名→经纬度代理（前端点击景点直接定位用）：
     * 前端传景点名，后端调高德 Web服务 place/text 搜索，取第一个 POI 的经纬度返回。
     * key 只在后端 .env 里，不暴露给前端。
     */
    @RequestMapping(value = "/amap/geocode", method = RequestMethod.GET)
    public java.util.Map<String, Object> amapGeocode(
            @RequestParam(name = "name", required = false, defaultValue = "") String name,
            @RequestParam(name = "city", required = false, defaultValue = "") String city) {
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("ok", false);
        if (name == null || name.isBlank()) {
            resp.put("error", "name 不能为空");
            return resp;
        }
        String key = ExternalServices.amapKey();
        if (key.isBlank()) {
            resp.put("error", "高德Key未配置，请在管理端填写或配置AMAP_KEY");
            return resp;
        }
        try {
            // 高德 Web服务 place/text 关键词搜索
            String qs = "keywords=" + java.net.URLEncoder.encode(name.trim(), "UTF-8")
                    + "&output=json&key=" + key + "&offset=1&extensions=base";
            if (city != null && !city.isBlank()) {
                qs += "&city=" + java.net.URLEncoder.encode(city.trim(), "UTF-8");
            }
            java.net.URL url = new java.net.URL(ExternalServices.amapPlaceSearchUrl(qs));
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            conn.disconnect();
            if (code != 200) {
                resp.put("error", "高德返回 HTTP " + code);
                return resp;
            }
            // 解析 pois[0].location（"经度,纬度"）
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, java.util.Map.class);
            Object poisObj = json.get("pois");
            if (!(poisObj instanceof java.util.List) || ((java.util.List<?>) poisObj).isEmpty()) {
                resp.put("error", "未找到该地点");
                return resp;
            }
            Object first = ((java.util.List<?>) poisObj).get(0);
            if (!(first instanceof java.util.Map)) {
                resp.put("error", "高德返回格式异常");
                return resp;
            }
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> poi = (java.util.Map<String, Object>) first;
            String location = poi.get("location") == null ? "" : poi.get("location").toString();
            String poiId = poi.get("id") == null ? "" : poi.get("id").toString();
            String poiName = poi.get("name") == null ? "" : poi.get("name").toString();
            if (location.isBlank() || !location.contains(",")) {
                resp.put("error", "高德未返回坐标");
                return resp;
            }
            String[] ll = location.split(",");
            resp.put("ok", true);
            resp.put("lng", ll[0]);
            resp.put("lat", ll[1]);
            resp.put("poiId", poiId);
            resp.put("poiName", poiName);
            return resp;
        } catch (Exception e) {
            log.error("高德 geocode 失败: {}", e.getMessage());
            resp.put("error", "调用失败: " + e.getMessage());
            return resp;
        }
    }
/** 读用户沉淀偏好（user_preferences 表；关闭默认注入或无记录返回 null） */
    private TripRequest.Preferences loadPreferences(long userId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT travel_style, budget_level, pace, avoid_list, use_by_default FROM user_preferences WHERE user_id = ?", userId);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            if (row.get("use_by_default") != null && ((Number) row.get("use_by_default")).intValue() != 1) {
                return null; // 用户关闭了默认注入
            }
            TripRequest.Preferences p = new TripRequest.Preferences();
            p.setTravelStyle(JsonUtils.parseStringList((String) row.get("travel_style")));
            p.setBudgetLevel(row.get("budget_level") == null ? null : ((Number) row.get("budget_level")).intValue());
            p.setPace(row.get("pace") == null ? null : ((Number) row.get("pace")).intValue());
            p.setAvoid(JsonUtils.parseStringList((String) row.get("avoid_list")));
            return p.isEmpty() ? null : p;
        } catch (Exception e) {
            log.warn("加载用户偏好失败: {}", e.getMessage());
            return null;
        }
    }

    /**
 * 读用户画像标签，拼成"【用户画像】..."行注入主管。
     * 无画像/异常返回空串，不影响主流程。
     */
    private String loadProfileTags(Long userId) {
        if (userId == null) return "";
        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT tags FROM user_profiles_tags WHERE user_id = ?", userId);
            if (rows.isEmpty() || rows.get(0).get("tags") == null) return "";
            Object raw = rows.get(0).get("tags");
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> tags = om.readValue(raw.toString(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            if (tags.isEmpty()) return "";
            List<String> parts = tags.entrySet().stream()
                    .sorted((a, b) -> Long.compare(
                            ((Number) b.getValue()).longValue(), ((Number) a.getValue()).longValue()))
                    .limit(8)
                    .map(e -> e.getKey() + (e.getValue() instanceof Number n && n.longValue() > 1
                            ? "×" + n.longValue() : ""))
                    .collect(java.util.stream.Collectors.toList());
            return "【用户画像】" + String.join("、", parts) + "\n";
        } catch (Exception e) {
            return "";
        }
    }
}
