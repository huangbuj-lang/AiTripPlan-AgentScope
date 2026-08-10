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
 * 用户登录注册接口
 * 注册：查重 → BCrypt加密 → 插入users表
 * 登录：查表 → BCrypt比对 → 存入Session
 * Session：浏览器带Cookie(sessionId)，服务端凭它识别登录用户
 */
@RestController
@RequestMapping("/api")
public class LoginController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(); // 密码加密器（BCrypt：单向哈希，数据库泄露也拿不到明文）

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

    /**
     * 登录（成功把用户信息存入Session）
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username, @RequestParam String password, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> users = jdbcTemplate.queryForList( // 查用户
                "SELECT * FROM users WHERE username = ?", username);
        if (users.isEmpty()) {
            result.put("success", false);
            result.put("message", "用户不存在，请先注册");
            return result;
        }
        Map<String, Object> user = users.get(0);
        String hashed = (String) user.get("password");
        if (!passwordEncoder.matches(password, hashed)) { // BCrypt比对密码
            result.put("success", false);
            result.put("message", "密码错误");
            return result;
        }
        session.setAttribute("userId", ((Number) user.get("id")).longValue()); // 登录成功：用户信息存入Session
        session.setAttribute("username", username);
        result.put("success", true);
        result.put("message", "登录成功");
        result.put("username", username);
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
        }
        return result;
    }
}
