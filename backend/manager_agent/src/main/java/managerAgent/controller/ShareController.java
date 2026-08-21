package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import utils.JsonUtils;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程分享接口（P0）：生成/撤销分享链接 + 免登录只读页。
 * share_code 短码指向 trip_plans，只读页复用 A4 渲染组件渲染 plan_json。
 */
@RestController
@RequestMapping("/api")
public class ShareController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"; // 去除易混淆字符

    private long userId(HttpSession session) {
        return ((Number) session.getAttribute("userId")).longValue();
    }

    /** 生成随机 share_code（10 位） */
    private String genCode() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /** 创建分享（幂等：同一行程重复分享则刷新 code；可设密码/有效期） */
    @PostMapping("/trips/{id}/share")
    public Map<String, Object> createShare(@PathVariable Long id,
                                           @RequestParam(required = false) String password,
                                           @RequestParam(required = false) Integer validDays,
                                           HttpSession session) {
        long uid = userId(session);
        Map<String, Object> result = new HashMap<>();
        // 校验行程归属
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trip_plans WHERE id = ? AND user_id = ?", Integer.class, id, uid);
        if (cnt == null || cnt == 0) {
            result.put("success", false);
            result.put("message", "行程不存在或无权分享");
            return result;
        }
        String code = genCode();
        String pwdHash = (password == null || password.isBlank()) ? null : passwordEncoder.encode(password);
        String expiresAt = null;
        if (validDays != null && validDays > 0) {
            expiresAt = jdbcTemplate.queryForObject(
                    "SELECT DATE_ADD(NOW(), INTERVAL ? DAY)", String.class, validDays);
        }
        // upsert（uk_trip 唯一，重复分享刷新）
        jdbcTemplate.update(
                "INSERT INTO trip_shares(trip_id, user_id, share_code, password_hash, is_public, expires_at) " +
                "VALUES (?, ?, ?, ?, 1, ?) " +
                "ON DUPLICATE KEY UPDATE share_code=VALUES(share_code), password_hash=VALUES(password_hash), " +
                "is_public=1, expires_at=VALUES(expires_at)",
                id, uid, code, pwdHash, expiresAt);
        result.put("success", true);
        result.put("shareCode", code);
        result.put("url", "/share.html?c=" + code);
        return result;
    }

    /** 撤销分享 */
    @DeleteMapping("/trips/{id}/share")
    public Map<String, Object> revokeShare(@PathVariable Long id, HttpSession session) {
        long uid = userId(session);
        int rows = jdbcTemplate.update("DELETE FROM trip_shares WHERE trip_id = ? AND user_id = ?", id, uid);
        Map<String, Object> result = new HashMap<>();
        result.put("success", rows > 0);
        result.put("message", rows > 0 ? "已撤销分享" : "该行程未分享");
        return result;
    }

    /**
     * 免登录只读页：按 code 取行程内容（结构化 JSON 优先，markdown 兜底）。
     * 可选密码校验；浏览量 +1。
     */
    @GetMapping("/share/{code}")
    public Map<String, Object> getSharedTrip(@PathVariable String code,
                                             @RequestParam(required = false) String password) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> shares = jdbcTemplate.queryForList(
                "SELECT * FROM trip_shares WHERE share_code = ?", code);
        if (shares.isEmpty()) {
            result.put("success", false);
            result.put("message", "分享不存在或已撤销");
            return result;
        }
        Map<String, Object> share = shares.get(0);
        // 有效期校验
        if (share.get("expires_at") != null) {
            Integer expired = jdbcTemplate.queryForObject(
                    "SELECT NOW() > ?", Integer.class, share.get("expires_at"));
            if (expired != null && expired == 1) {
                result.put("success", false);
                result.put("message", "分享链接已过期");
                return result;
            }
        }
        // 密码校验
        if (share.get("password_hash") != null) {
            if (password == null || !passwordEncoder.matches(password, (String) share.get("password_hash"))) {
                result.put("success", false);
                result.put("needPassword", true);
                result.put("message", "该分享需要密码");
                return result;
            }
        }
        // 取行程内容
        Long tripId = ((Number) share.get("trip_id")).longValue();
        Map<String, Object> trip = jdbcTemplate.queryForMap(
                "SELECT city, days, plan_content, plan_json FROM trip_plans WHERE id = ?", tripId);
        jdbcTemplate.update("UPDATE trip_shares SET view_count = view_count + 1 WHERE share_code = ?", code);
        result.put("success", true);
        result.put("city", trip.get("city"));
        result.put("days", trip.get("days"));
        result.put("plan_content", trip.get("plan_content"));
        result.put("plan_json", trip.get("plan_json"));
        result.put("view_count", ((Number) share.get("view_count")).intValue() + 1);
        return result;
    }

    /** 查询某行程的分享状态（前端"我的行程"展示分享链接） */
    @GetMapping("/trips/{id}/share")
    public Map<String, Object> getShareStatus(@PathVariable Long id, HttpSession session) {
        long uid = userId(session);
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> shares = jdbcTemplate.queryForList(
                "SELECT share_code, view_count, created_at FROM trip_shares WHERE trip_id = ? AND user_id = ?", id, uid);
        if (shares.isEmpty()) {
            result.put("success", true);
            result.put("shared", false);
        } else {
            result.put("success", true);
            result.put("shared", true);
            result.put("shareCode", shares.get(0).get("share_code"));
            result.put("viewCount", shares.get(0).get("view_count"));
            result.put("url", "/share.html?c=" + shares.get(0).get("share_code"));
        }
        return result;
    }
}
