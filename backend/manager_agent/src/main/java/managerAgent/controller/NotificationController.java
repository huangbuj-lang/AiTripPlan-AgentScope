package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知中心（P1）：REST 查询 + 标记已读 + SSE 实时推送。
 * 通知作为持久化事件落 DB（离线可达），SSE 按 userId 广播到在线窗口。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    private long uid(HttpSession session) {
        return ((Number) session.getAttribute("userId")).longValue();
    }

    /** 通知列表（分页，新→旧） */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false, defaultValue = "0") int page,
                                    @RequestParam(required = false, defaultValue = "20") int pageSize,
                                    HttpSession session) {
        long userId = uid(session);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("notifications", jdbcTemplate.queryForList(
                "SELECT id, type, content, link_url, is_read, created_at FROM notifications " +
                "WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                userId, pageSize, page * pageSize));
        return result;
    }

    /** 未读数量 */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(HttpSession session) {
        long userId = uid(session);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = 0", Integer.class, userId));
        return result;
    }

    /** 全部标记已读 */
    @PutMapping("/read-all")
    public Map<String, Object> readAll(HttpSession session) {
        long userId = uid(session);
        jdbcTemplate.update("UPDATE notifications SET is_read = 1 WHERE user_id = ?", userId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    /** SSE 通知流：按 userId 订阅实时推送 */
    @GetMapping("/stream")
    public SseEmitter stream(HttpSession session) {
        long userId = uid(session);
        return NotificationHub.subscribe(userId);
    }
}
