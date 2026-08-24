package managerAgent.controller;

import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import managerAgent.hook.EventStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import utils.CircuitBreaker;
import utils.Config;
import utils.NacosUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;

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

    // ==================== 系统状态 ====================

    /** 各 Agent 健康/注册状态、端口、Nacos 连接情况 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> r = new HashMap<>();

        Map<String, Object> manager = new HashMap<>();
        manager.put("name", "Orchestrator"); // 主管（确定性编排器 TripOrchestrator）
        manager.put("label", "主管");
        manager.put("status", "running");
        manager.put("port", 8081);
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
        r.put("success", true);
        return r;
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

    // ==================== 模型监控 ====================

    /** 熔断器状态 / 失败计数 / 主备模型 / 限速 QPS */
    // ==================== ★模型配置热更（Java侧/Python侧两组） ====================

    /** 读当前模型配置（key 打码显示；含主模型/备用模型/Python侧三组） */
    @GetMapping("/model-config")
    public Map<String, Object> getModelConfig() {
        Map<String, Object> r = new java.util.HashMap<>();
        String jk = utils.ModelConfigHolder.apiKey();
        r.put("java", Map.of(
                "url", utils.ModelConfigHolder.baseUrl(),
                "name", utils.ModelConfigHolder.modelName(),
                "keyMasked", maskKey(jk)));
        // ★备用模型组（熔断降级用，OpenAI 兼容接口）
        String fk = utils.ModelConfigHolder.fbApiKey();
        r.put("fallback", Map.of(
                "url", utils.ModelConfigHolder.fbBaseUrl(),
                "name", utils.ModelConfigHolder.fbModelName(),
                "keyMasked", maskKey(fk)));
        Map<String, Object> py = readPyLlmConfig();
        py.put("keyMasked", maskKey((String) py.get("key")));
        py.remove("key");
        r.put("python", py);
        r.put("success", true);
        return r;
    }

    /** 保存模型配置：写 .env + Java 热更 + 重启子Agent。主模型/备用模型均为"任一字段非空即保存该组" */
    @PostMapping("/model-config")
    public Map<String, Object> saveModelConfig(@RequestParam(required = false) String javaUrl,
                                               @RequestParam(required = false) String javaKey,
                                               @RequestParam(required = false) String javaName,
                                               @RequestParam(required = false) String fbUrl,
                                               @RequestParam(required = false) String fbKey,
                                               @RequestParam(required = false) String fbName,
                                               @RequestParam(required = false) String pyUrl,
                                               @RequestParam(required = false) String pyKey,
                                               @RequestParam(required = false) String pyName) {
        Map<String, Object> r = new java.util.HashMap<>();
        try {
            StringBuilder note = new StringBuilder();
            boolean javaChanged = (javaUrl != null && !javaUrl.isBlank())
                    || (javaKey != null && !javaKey.isBlank())
                    || (javaName != null && !javaName.isBlank());
            if (javaChanged) {
                // Java 侧：热更内存配置 + 写 .env（重启后仍生效）。任一字段非空即整组处理，空字段保持原值
                utils.ModelConfigHolder.update(javaUrl, javaKey, javaName);
                if (javaUrl != null && !javaUrl.isBlank()) upsertEnv("MODEL_BASE_URL", javaUrl);
                if (javaKey != null && !javaKey.isBlank()) upsertEnv("MODEL_API_KEY", javaKey);
                if (javaName != null && !javaName.isBlank()) upsertEnv("MODEL_NAME", javaName);
                managerAgent.tool.LlmClientReset.reset(); // 下次构建模型用新配置
                // ★子Agent(8082/8085)是独立JVM，模型配置启动时固化——写完.env后异步重启它们才用新模型
                java.lang.Thread t2 = new Thread(() -> {
                    try { Thread.sleep(800); } catch (Exception ignore) {}
                    restartSubAgents();
                });
                t2.setDaemon(true);
                t2.start();
                note.append("Java侧已热更；子Agent正在重启以加载新模型；");
            }
            // ★备用模型组：任一字段非空即热更 + 写 .env（子Agent重启同样加载）
            boolean fbChanged = (fbUrl != null && !fbUrl.isBlank())
                    || (fbKey != null && !fbKey.isBlank())
                    || (fbName != null && !fbName.isBlank());
            if (fbChanged) {
                utils.ModelConfigHolder.updateFallback(fbUrl, fbKey, fbName);
                if (fbUrl != null && !fbUrl.isBlank()) upsertEnv("FALLBACK_MODEL_BASE_URL", fbUrl);
                if (fbKey != null && !fbKey.isBlank()) upsertEnv("FALLBACK_MODEL_API_KEY", fbKey);
                if (fbName != null && !fbName.isBlank()) upsertEnv("FALLBACK_MODEL_NAME", fbName);
                note.append("备用模型已热更；");
            }
            if (pyUrl != null && !pyUrl.isBlank() || pyKey != null && !pyKey.isBlank()
                    || pyName != null && !pyName.isBlank()) {
                // Python 侧：写统一配置文件 llm_config.json
                savePyLlmConfig(pyUrl, pyKey, pyName);
                note.append("Python侧已更新；");
            }
            r.put("success", true);
            r.put("message", note.length() == 0 ? "没有需要保存的修改" : note.toString());
        } catch (Exception e) {
            r.put("success", false);
            r.put("message", "保存失败：" + e.getMessage());
        }
        return r;
    }

    /** 打码 key */
    private String maskKey(String k) {
        return (k == null || k.length() < 10) ? "" : k.substring(0, 6) + "****" + k.substring(k.length() - 4);
    }

    /** 写/更新 .env 中的键（保持其他行不变） */
/**
     * ★重启全部本地服务(含子Agent 8082/8085)：独立JVM进程的模型配置在启动时固化，
     * 管理端热更只影响主管进程——统一调 start-local.sh stop+start 让全链路换模型。
     */
    private synchronized void restartSubAgents() {
        try {
            String base = System.getProperty("user.dir");
            ProcessBuilder stop = new ProcessBuilder("bash", "start-local.sh", "stop");
            stop.directory(new java.io.File(base));
            stop.redirectErrorStream(true);
            stop.start().waitFor();
            ProcessBuilder start = new ProcessBuilder("bash", "start-local.sh");
            start.directory(new java.io.File(base));
            start.redirectErrorStream(true);
            start.start().waitFor();
            log.info("全服务已重启(子Agent加载新模型配置)");
        } catch (Exception e) {
            log.warn("重启子Agent异常: {}", e.getMessage());
        }
    }


        private synchronized void upsertEnv(String key, String value) throws Exception {
        java.io.File f = new java.io.File(System.getProperty("user.dir"), ".env");
        java.util.List<String> lines = new java.util.ArrayList<>();
        boolean found = false;
        if (f.exists()) {
            for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                if (line.startsWith(key + "=")) {
                    lines.add(key + "=" + value);
                    found = true;
                } else {
                    lines.add(line);
                }
            }
        }
        if (!found) lines.add(key + "=" + value);
        java.nio.file.Files.write(f.toPath(), lines);
    }

    /** Python 侧统一配置文件 data-pipeline/llm_config.json */
    private java.io.File pyConfigFile() {
        return new java.io.File(System.getProperty("user.dir"), "data-pipeline/llm_config.json");
    }

    private Map<String, Object> readPyLlmConfig() {
        Map<String, Object> m = new java.util.HashMap<>();
        try {
            java.io.File f = pyConfigFile();
            if (f.exists()) {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                m = om.readValue(f, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            }
        } catch (Exception ignored) { }
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
        r.put("primaryModel", Config.get("MODEL_NAME", "deepseek-v4-flash"));
        r.put("fallbackModel", Config.get("FALLBACK_MODEL_NAME", "qwen3.7-max"));
        r.put("maxQps", Config.getDouble("MODEL_MAX_QPS", 3));
        r.put("maxRetries", Config.getInt("MODEL_MAX_RETRIES", 3));
        r.put("success", true);
        return r;
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
        jdbcTemplate.update("DELETE FROM user_profiles_tags WHERE user_id = ?", id); // ★画像标签同步清理，防孤儿数据残留弹窗
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
        // ★城市归一化：用户输入("成都三日")→标准城市名("成都")，前缀最长匹配 attractions 城市库
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
// ==================== 数据中心（数据管线可视化，独立新增） ====================

    /** 表存在性容错查询：表不存在返回空列表 */
    private java.util.List<Map<String, Object>> qRowsSafe(String sql) {
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    /**
     * ★数据统计明细：点击管理端统计卡片后查看具体数据。
     * type=attractions/foods/hotels/cities/profiles
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
            // columns 顺序与 SELECT 顺序一致：重新按 columns 长度对齐（row.keySet() 无序风险 → 直接用 values() 的原始顺序不可靠）
            // 为稳妥起见这里直接把行转成对象数组交给前端按 key 显示
            List<List<Object>> aligned = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                List<Object> line = new ArrayList<>();
                // ★首格=隐藏主键id（前端编辑用），columns 展示时跳过
                line.add(row.get("id") == null ? "-" : row.get("id").toString());
                for (String col : columns) {
                    Object v = row.get(colKey(type, col));
                    line.add(v == null ? "-" : v.toString());
                }
                aligned.add(line);
            }
            r.put("columns", columns);
            // ★画像明细特殊处理：标签JSON解析为"标签×次数"顿号串（不显示原始JSON），时间只留秒级
            if ("profiles".equals(type)) {
                List<List<Object>> pretty = new ArrayList<>();
                for (List<Object> line : aligned) {
                    List<Object> out = new ArrayList<>(line); // [用户名, 用户ID, tagsJSON, updatedAt]
                    if (out.size() >= 4) {
                        // tags 保留原始JSON（前端标签云按类别着色+权重渲染），只去掉时间毫秒
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
     * ★手动编辑数据统计明细（景点/美食/酒店）：管理员修正爬取数据
     * type=attractions/foods/hotels，field=name/city/rating/cost/address
     * 注意：cost 字段写入特征表（定价读特征表），其余写主表
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
                // 定价读特征表：景点→ticket_price，酒店→cost；主表cost也同步
                String featureSql;
                if (type.equals("attractions")) {
                    featureSql = "UPDATE attractions_features SET ticket_price='" + val.replace("'", "''")
                            + "' WHERE src_id=" + id;
                } else {
                    featureSql = "UPDATE hotels_features SET cost='" + val.replace("'", "''")
                            + "' WHERE src_id=" + id;
                }
                jdbcTemplate.update(featureSql);
                jdbcTemplate.update("UPDATE " + tableName(type) + " SET cost='" + val.replace("'", "''") + "' WHERE id=" + id);
            } else {
                String col = field.equals("name") ? "name" : field;
                jdbcTemplate.update("UPDATE " + tableName(type) + " SET " + col + "='" + val.replace("'", "''")
                        + "' WHERE id=" + id);
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

    /** 动态定价专用刷新：只跑 pricing.py（秒级），不碰采集管线，手改数据不会被覆盖 */
    @PostMapping("/pricing/run")
    public Map<String, Object> runPricing() {
        Map<String, Object> r = new HashMap<>();
        try {
            String projectDir = System.getProperty("user.dir");
            // ★多候选路径：user.dir 可能是项目根或 backend/manager_agent，都试
            java.io.File script = null;
            for (String rel : new String[]{"data-pipeline/pricing.py", "../../data-pipeline/pricing.py"}) {
                java.io.File f = new java.io.File(projectDir, rel);
                if (f.exists()) { script = f; break; }
            }
            if (script == null) {
                java.io.File fallback = new java.io.File(projectDir + "/../data-pipeline/pricing.py");
                if (fallback.exists()) script = fallback;
            }
            if (script == null) { r.put("success", false); r.put("message", "找不到 pricing.py"); return r; }
            ProcessBuilder pb = new ProcessBuilder("python3", script.getCanonicalPath());
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
/** 数据管线一键更新（异步执行 python3 data-pipeline/update_all.py，立即返回任务ID） */
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
        String projectDir = System.getProperty("user.dir");
        // manager_agent 工作目录为 backend/manager_agent，项目根在上两级
        java.io.File script = new java.io.File(projectDir, "../../data-pipeline/update_all.py");
        if (!script.exists()) {
            script = new java.io.File("D:/hermes-agent/data-pipeline/update_all.py"); // 兜底（不应命中）
        }
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
                ProcessBuilder pb = new ProcessBuilder("python3", scriptPath);
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
