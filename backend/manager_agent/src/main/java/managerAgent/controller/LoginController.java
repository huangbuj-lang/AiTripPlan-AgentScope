package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
/**
 * 用户登录注册接口
 */
@RestController
@RequestMapping("/api")
public class LoginController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(); // BCrypt密码加密器

    // 登录失败计数（防爆破）：连续失败达上限后短时间冷却
    private static final java.util.concurrent.ConcurrentHashMap<String, FailEntry> LOGIN_FAILS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_FAILS = 5;                 // 连续失败上限
    private static final long COOLDOWN_MS = 60_000L;        // 冷却时长（60秒）

    /** 单个账号的失败记录 */
    private static class FailEntry {
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        volatile long lastFailMs = 0;
    }

    /**
     * 注册新用户
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestParam String username, @RequestParam String password) {
        Map<String, Object> result = new HashMap<>();
        if (username == null || username.isBlank() || password == null || password.length() < 6) { // 基本校验
            result.put("success", false);
            result.put("message", "用户名不能为空，密码至少6位");
            return result;
        }
        Integer count = jdbcTemplate.queryForObject( // 用户名查重
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) {
            result.put("success", false);
            result.put("message", "用户名已存在，请换一个");
            return result;
        }
        String hashed = passwordEncoder.encode(password); // 加密密码后插入
        jdbcTemplate.update("INSERT INTO users(username, password) VALUES (?, ?)", username, hashed);
        result.put("success", true);
        result.put("message", "注册成功，请登录");
        return result;
    }

    /** 判断账号是否处于失败冷却期 */
    private boolean inCooldown(String username) {
        FailEntry f = LOGIN_FAILS.get(username);
        if (f == null) return false;
        return f.count.get() >= MAX_FAILS && (System.currentTimeMillis() - f.lastFailMs) < COOLDOWN_MS;
    }

    /** 记录一次登录失败 */
    private void recordFail(String username) {
        FailEntry f = LOGIN_FAILS.computeIfAbsent(username, k -> new FailEntry());
        f.count.incrementAndGet();
        f.lastFailMs = System.currentTimeMillis();
    }

    /**
     * 登录（成功把用户信息存入Session）
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username, @RequestParam String password, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        if (inCooldown(username)) { // 防爆破：冷却期内拒绝尝试
            result.put("success", false);
            result.put("message", "尝试次数过多，请 " + (COOLDOWN_MS / 1000) + " 秒后再试");
            return result;
        }
        List<Map<String, Object>> users = jdbcTemplate.queryForList( // 查用户
                "SELECT * FROM users WHERE username = ?", username);
        if (users.isEmpty()) {
            recordFail(username); // 用户不存在也计入失败，防用户名枚举撞库
            result.put("success", false);
            result.put("message", "用户不存在，请先注册");
            return result;
        }
        Map<String, Object> user = users.get(0);
        String hashed = (String) user.get("password");
        if (!passwordEncoder.matches(password, hashed)) { // BCrypt比对密码
            recordFail(username);
            result.put("success", false);
            result.put("message", "密码错误");
            return result;
        }
        LOGIN_FAILS.remove(username); // 登录成功：清除失败记录
        session.setAttribute("userId", ((Number) user.get("id")).longValue()); // 登录成功：用户信息存入Session
        session.setAttribute("username", username);
        // 角色：优先读库 role 字段（老库无此列则默认 user）；ADMIN_USERNAME 环境变量可强制指定管理员（零迁移）
        String role = user.get("role") == null ? "user" : user.get("role").toString();
        String adminUser = System.getenv("ADMIN_USERNAME");
        if (adminUser != null && !adminUser.isBlank() && adminUser.equals(username)) {
            role = "admin";
        }
        session.setAttribute("role", role);
        result.put("success", true);
        result.put("message", "登录成功");
        result.put("username", username);
        result.put("role", role);
        return result;
    }

    /**
     * 退出登录（销毁Session）
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已退出登录");
        return result;
    }

    /**
     * 检查当前是否已登录（前端页面加载时调用）
     */
    @GetMapping("/check-login")
    public Map<String, Object> checkLogin(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        Object username = session.getAttribute("username");
        if (username == null) {
            result.put("success", false);
            result.put("message", "未登录");
        } else {
            result.put("success", true);
            result.put("username", username);
            result.put("role", session.getAttribute("role"));
        }
        return result;
    }
}
