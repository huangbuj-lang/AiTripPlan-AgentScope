package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import utils.JsonUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人中心接口（P0）：资料编辑 / 修改密码 / 旅行偏好 / 注销账号。
 * 资料（昵称/头像/邮箱/简介）存 user_profiles，认证核心仍在 users 表。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private long userId(HttpSession session) {
        return ((Number) session.getAttribute("userId")).longValue();
    }

    /** 确保 user_profiles 行存在（惰性创建，注册时未建） */
    private void ensureProfile(long uid) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO user_profiles(user_id) VALUES (?)", uid);
    }

    /** 查询资料（username/角色/create_time 来自 users，其余来自 user_profiles） */
    @GetMapping
    public Map<String, Object> getProfile(HttpSession session) {
        long uid = userId(session);
        ensureProfile(uid);
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT username, role, create_time FROM users WHERE id = ?", uid);
        Map<String, Object> profile = jdbcTemplate.queryForMap(
                "SELECT nickname, avatar_url, email, bio FROM user_profiles WHERE user_id = ?", uid);
        result.put("success", true);
        result.put("username", user.get("username"));
        result.put("role", user.get("role"));
        result.put("create_time", user.get("create_time"));
        result.put("nickname", profile.get("nickname"));
        result.put("avatar_url", profile.get("avatar_url"));
        result.put("email", profile.get("email"));
        result.put("bio", profile.get("bio"));
        return result;
    }

    /** 更新资料（昵称/头像/邮箱/简介） */
    @PutMapping
    public Map<String, Object> updateProfile(@RequestParam(required = false) String nickname,
                                             @RequestParam(required = false) String avatarUrl,
                                             @RequestParam(required = false) String email,
                                             @RequestParam(required = false) String bio,
                                             HttpSession session) {
        long uid = userId(session);
        ensureProfile(uid);
        Map<String, Object> result = new HashMap<>();
        try {
            // 邮箱去重（非空才唯一）；空串转 NULL
            String em = (email == null || email.isBlank()) ? null : email.trim();
            if (em != null) {
                Integer cnt = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_profiles WHERE email = ? AND user_id <> ?", Integer.class, em, uid);
                if (cnt != null && cnt > 0) {
                    result.put("success", false);
                    result.put("message", "该邮箱已被其他账号使用");
                    return result;
                }
            }
            jdbcTemplate.update(
                    "UPDATE user_profiles SET nickname = ?, avatar_url = ?, email = ?, bio = ? WHERE user_id = ?",
                    nickname == null || nickname.isBlank() ? null : nickname.trim(),
                    avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl.trim(),
                    em,
                    bio == null || bio.isBlank() ? null : bio.trim(),
                    uid);
            result.put("success", true);
            result.put("message", "资料已更新");
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "更新失败：" + e.getMessage());
            return result;
        }
    }

    /** 修改密码：旧密码校验 + bump pwd_version（强制全端下线） */
    @PutMapping("/password")
    public Map<String, Object> changePassword(@RequestParam String oldPassword,
                                              @RequestParam String newPassword,
                                              HttpSession session) {
        long uid = userId(session);
        Map<String, Object> result = new HashMap<>();
        if (newPassword == null || newPassword.length() < 6) {
            result.put("success", false);
            result.put("message", "新密码至少6位");
            return result;
        }
        String hashed = jdbcTemplate.queryForObject(
                "SELECT password FROM users WHERE id = ?", String.class, uid);
        if (hashed == null || !passwordEncoder.matches(oldPassword, hashed)) {
            result.put("success", false);
            result.put("message", "旧密码错误");
            return result;
        }
        String newHashed = passwordEncoder.encode(newPassword);
        // bump pwd_version → 其它会话在 LoginInterceptor 校验失败被强制下线
        jdbcTemplate.update("UPDATE users SET password = ?, pwd_version = pwd_version + 1 WHERE id = ?", newHashed, uid);
        // 当前会话同步新版本号，保持登录
        session.setAttribute("pwdVersion", ((Number) jdbcTemplate.queryForMap(
                "SELECT pwd_version FROM users WHERE id = ?", uid).get("pwd_version")).intValue());
        result.put("success", true);
        result.put("message", "密码已修改，其它设备已下线");
        return result;
    }

    /** 查询旅行偏好 */
    @GetMapping("/preferences")
    public Map<String, Object> getPreferences(HttpSession session) {
        long uid = userId(session);
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT travel_style, budget_level, pace, avoid_list, use_by_default FROM user_preferences WHERE user_id = ?", uid);
        result.put("success", true);
        if (rows.isEmpty()) {
            result.put("travel_style", null);
            result.put("budget_level", null);
            result.put("pace", null);
            result.put("avoid_list", null);
            result.put("use_by_default", 1);
        } else {
            Map<String, Object> row = rows.get(0);
            result.put("travel_style", row.get("travel_style"));
            result.put("budget_level", row.get("budget_level"));
            result.put("pace", row.get("pace"));
            result.put("avoid_list", row.get("avoid_list"));
            result.put("use_by_default", row.get("use_by_default"));
        }
        return result;
    }

    /** 保存旅行偏好（travelStyle/avoid 传 JSON 数组字符串） */
    @PutMapping("/preferences")
    public Map<String, Object> updatePreferences(@RequestParam(required = false) String travelStyle,
                                                 @RequestParam(required = false) Integer budgetLevel,
                                                 @RequestParam(required = false) Integer pace,
                                                 @RequestParam(required = false) String avoidList,
                                                 @RequestParam(required = false, defaultValue = "1") Integer useByDefault,
                                                 HttpSession session) {
        long uid = userId(session);
        Map<String, Object> result = new HashMap<>();
        // 校验 JSON 数组合法性
        if (travelStyle != null && !travelStyle.isBlank() && JsonUtils.parseStringList(travelStyle) == null) {
            result.put("success", false);
            result.put("message", "风格标签格式非法");
            return result;
        }
        if (avoidList != null && !avoidList.isBlank() && JsonUtils.parseStringList(avoidList) == null) {
            result.put("success", false);
            result.put("message", "回避项格式非法");
            return result;
        }
        String style = (travelStyle == null || travelStyle.isBlank()) ? null : travelStyle;
        String avoid = (avoidList == null || avoidList.isBlank()) ? null : avoidList;
        jdbcTemplate.update(
                "INSERT INTO user_preferences(user_id, travel_style, budget_level, pace, avoid_list, use_by_default) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE travel_style=VALUES(travel_style), budget_level=VALUES(budget_level), " +
                "pace=VALUES(pace), avoid_list=VALUES(avoid_list), use_by_default=VALUES(use_by_default)",
                uid, style, budgetLevel, pace, avoid, useByDefault == null ? 1 : useByDefault);
        result.put("success", true);
        result.put("message", "偏好已保存");
        return result;
    }

    /** 注销账号：逻辑删除（status=2）+ 销毁会话 */
    @DeleteMapping
    public Map<String, Object> deleteAccount(HttpSession session) {
        long uid = userId(session);
        jdbcTemplate.update("UPDATE users SET status = 2 WHERE id = ?", uid);
        session.invalidate();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "账号已注销");
        return result;
    }
}
