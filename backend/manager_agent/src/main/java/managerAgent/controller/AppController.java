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

/**
 * 用户与Agent互动的HTTP接口
 */
@Slf4j
@RestController
public class AppController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    // 全局并发任务数上限（环境变量配置，默认1）
    private static final int MAX_CONCURRENT_TASKS = Integer.parseInt(
            System.getenv().getOrDefault("MAX_CONCURRENT_TASKS", "1"));

    // 异步任务线程池（大小=并发上限）
    private static final java.util.concurrent.ExecutorService TASK_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(MAX_CONCURRENT_TASKS);

    // 并发信号量：达到上限时拒绝新任务
    private static final java.util.concurrent.Semaphore TASK_SLOTS =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_TASKS);

    /** 并发上限（管理端任务监控展示） */
    public static int maxConcurrent() { return MAX_CONCURRENT_TASKS; }

    /** 当前占用的并发槽数（管理端任务监控展示） */
    public static int inUseSlots() { return MAX_CONCURRENT_TASKS - TASK_SLOTS.availablePermits(); }

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
        if (!TASK_SLOTS.tryAcquire()) {
            return "⚠️ 有任务正在执行中（最多同时 " + MAX_CONCURRENT_TASKS + " 个），请等当前任务完成后再试！";
        }

        // 绑定会话归属：防止他人凭 sessionId 越权读取/取消本用户任务
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj != null) {
            EventStore.bindOwner(sessionId, ((Number) userIdObj).longValue());
        }

        EventStore.clear(sessionId); // 清空该会话的旧事件

        TASK_POOL.submit(() -> { // 异步执行任务
            try {
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
                EventStore.setResult(sessionId, "⏹ 任务已由用户中断。"); // 用户取消的友好提示（类型化判断）
            } catch (Exception e) {
                EventStore.setResult(sessionId, "❌ 任务执行出错：" + e.getMessage()); // 缓存错误信息
                log.error("任务执行失败: {}", e.getMessage(), e);
            } finally {
                TASK_SLOTS.release(); // 释放并发槽位
            }
        });

        return "✅ 任务已接收，正在后台执行中！请查看思考过程，完成后结果自动显示。";
    }

    /**
     * 获取任务最终结果（未完成时返回提示）（按会话隔离）
     */
    @RequestMapping(value = "/result", method = RequestMethod.GET)
    public String result(@RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,
                         HttpSession session) {
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
        String key = System.getenv().getOrDefault("AMAP_KEY", "");
        if (key.isBlank()) {
            resp.put("error", "AMAP_KEY 未配置");
            return resp;
        }
        try {
            // 高德 Web服务 place/text 关键词搜索
            String qs = "keywords=" + java.net.URLEncoder.encode(name.trim(), "UTF-8")
                    + "&output=json&key=" + key + "&offset=1&extensions=base";
            if (city != null && !city.isBlank()) {
                qs += "&city=" + java.net.URLEncoder.encode(city.trim(), "UTF-8");
            }
            java.net.URL url = new java.net.URL("https://restapi.amap.com/v3/place/text?" + qs);
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
