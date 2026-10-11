package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import managerAgent.service.NotificationService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** 通知中心（P1）：REST 查询 + 标记已读 + SSE 实时推送。 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Resource
    private NotificationService notificationService;

    private long uid(HttpSession session) {
        return ((Number) session.getAttribute("userId")).longValue();
    }

    /** 通知列表（分页，新->旧） */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false, defaultValue = "0") int page,
                                    @RequestParam(required = false, defaultValue = "20") int pageSize,
                                    HttpSession session) {
        return notificationService.list(uid(session), page, pageSize);
    }

    /** 未读数量 */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(HttpSession session) {
        return notificationService.unreadCount(uid(session));
    }

    /** 全部标记已读 */
    @PutMapping("/read-all")
    public Map<String, Object> readAll(HttpSession session) {
        return notificationService.markAllRead(uid(session));
    }

    /** SSE 通知流：按 userId 订阅实时推送 */
    @GetMapping("/stream")
    public SseEmitter stream(HttpSession session) {
        return NotificationHub.subscribe(uid(session));
    }
}
