package managerAgent.service;

import jakarta.annotation.Resource;
import managerAgent.repository.LoginRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录注册业务：密码加密与比对、注册校验、防爆破冷却、账号状态与角色判定。
 * 只做业务，SQL 在 LoginRepository；Session 读写留在控制器（HTTP 层）。
 */
@Service
public class LoginService {

    @Resource
    private LoginRepository loginRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(); // BCrypt密码加密器

    //登录失败计数（防爆破）：连续失败达上限后短时间冷却
    private static final ConcurrentHashMap<String, FailEntry> LOGIN_FAILS = new ConcurrentHashMap<>();
    private static final int MAX_FAILS = 5;                 // 连续失败上限
    private static final long COOLDOWN_MS = 60_000L;        // 冷却时长（60秒）

    /** 单个账号的失败记录 */
    private static class FailEntry {
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        volatile long lastFailMs = 0;
    }

    /** 登录结果：控制器据此写 Session，并拼装响应 */
    public record LoginResult(boolean success, String message, Long userId, String role,
                              Integer status, Integer pwdVersion) {
    }

    /** 注册新用户 */
    public Map<String, Object> register(String username, String password) {
        Map<String, Object> result = new HashMap<>();
        if (username == null || username.isBlank() || password == null || password.length() < 6) { // 基本校验
            result.put("success", false);
            result.put("message", "用户名不能为空，密码至少6位");
            return result;
        }
        if (loginRepository.countByUsername(username) > 0) { // 用户名查重
            result.put("success", false);
            result.put("message", "用户名已存在，请换一个");
            return result;
        }
        loginRepository.insert(username, passwordEncoder.encode(password)); // 加密密码后插入
        result.put("success", true);
        result.put("message", "注册成功，请登录");
        return result;
    }

    /** 登录校验：返回结果供控制器写入 Session */
    public LoginResult login(String username, String password) {
        if (inCooldown(username)) { // 防爆破：冷却期内拒绝尝试
            return new LoginResult(false, "尝试次数过多，请 " + (COOLDOWN_MS / 1000) + " 秒后再试", null, null, null, null);
        }
        List<Map<String, Object>> users = loginRepository.findByUsername(username); // 查用户
        if (users.isEmpty()) {
            recordFail(username); // 用户不存在也计入失败，防用户名枚举撞库
            return new LoginResult(false, "用户名或密码错误", null, null, null, null);
        }
        Map<String, Object> user = users.get(0);
        String hashed = (String) user.get("password");
        if (!passwordEncoder.matches(password, hashed)) { // BCrypt比对密码
            recordFail(username);
            return new LoginResult(false, "用户名或密码错误", null, null, null, null);
        }
        LOGIN_FAILS.remove(username); // 登录成功：清除失败记录
        //P0：账号状态校验（注销/封禁禁止登录）
        int status = user.get("status") == null ? 1 : ((Number) user.get("status")).intValue();
        if (status != 1) {
            return new LoginResult(false, "账号已被禁用，请联系管理员", null, null, null, null);
        }
        //角色：优先读库 role 字段（老库无此列则默认 user）
        String role = user.get("role") == null ? "user" : user.get("role").toString();
        String adminUser = System.getenv("ADMIN_USERNAME");
        if (adminUser != null && !adminUser.isBlank() && adminUser.equals(username)) {
            role = "admin";
        }
        //P0：凭证版本号 + 账号状态一并回给控制器入会话，LoginInterceptor 据此强制下线
        int pwdVersion = user.get("pwd_version") == null ? 0 : ((Number) user.get("pwd_version")).intValue();
        return new LoginResult(true, "登录成功", ((Number) user.get("id")).longValue(), role, status, pwdVersion);
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
}
