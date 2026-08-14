package managerAgent.controller;

import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import jakarta.annotation.Resource;
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
}
