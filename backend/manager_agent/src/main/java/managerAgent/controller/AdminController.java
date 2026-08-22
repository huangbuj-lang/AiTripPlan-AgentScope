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
        manager.put("name", "ManagerAgent");
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
        agents.add(agentStatus("RouteMakingAgent", 8082));
        agents.add(agentStatus("TripPlannerAgent", 8085));
        r.put("agents", agents);

        r.put("success", true);
        return r;
    }

    /** 单个远程 Agent 的注册状态（查 Nacos 名片是否存在） */
    private Map<String, Object> agentStatus(String name, int port) {
        Map<String, Object> a = new HashMap<>();
        a.put("name", name);
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

    /** 删除用户（连同其名下行程一起删） */
    @DeleteMapping("/users")
    public Map<String, Object> deleteUser(@RequestParam Long id) {
        jdbcTemplate.update("DELETE FROM trip_plans WHERE user_id = ?", id);
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

    /** 数据管线统计：各表行数（表不存在返回0） */
    @GetMapping("/data-stats")
    public Map<String, Object> dataStats() {
        Map<String, Object> r = new HashMap<>();
        r.put("attractions", qIntSafe("SELECT COUNT(*) FROM attractions"));
        r.put("foods", qIntSafe("SELECT COUNT(*) FROM foods"));
        r.put("hotels", qIntSafe("SELECT COUNT(*) FROM hotels"));
        r.put("cities", qIntSafe("SELECT COUNT(DISTINCT city) FROM attractions"));
        r.put("feedbacks", qIntSafe("SELECT COUNT(*) FROM plan_feedback"));
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
        r.put("rows", qRowsSafe("SELECT city, hotel_base, hotel_price, ticket_base, ticket_price, factors FROM pricing_output ORDER BY hotel_price DESC"));
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

    /** 用户画像 + 反馈统计 */
    @GetMapping("/profiles")
    public Map<String, Object> profiles() {
        Map<String, Object> r = new HashMap<>();
        r.put("profiles", qRowsSafe("SELECT t.user_id, u.username, t.tags FROM user_profiles_tags t LEFT JOIN users u ON u.id = t.user_id"));
        r.put("feedback", qRowsSafe("SELECT action, COUNT(*) cnt FROM plan_feedback GROUP BY action"));
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
