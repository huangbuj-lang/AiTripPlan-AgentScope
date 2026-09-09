package managerAgent.controller;

import jakarta.servlet.http.HttpSession;
import managerAgent.hook.EventStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** 事件查询接口：按会话查询Agent运行事件（供前端轮询） */
@RestController
@RequestMapping("/events")
public class EventController {

        /** 查询该会话的事件列表 */
    @GetMapping
    public List<Map<String, String>> events(
            @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,
            HttpSession session) {
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null || !EventStore.isOwner(sessionId, ((Number) userIdObj).longValue())) {
            return List.of(); // 非本人会话：返回空，不泄露他人事件
        }
        return EventStore.all(sessionId);
    }

        /** SSE 实时事件流（替代前端轮询，阶段四 F-3） */
    @GetMapping("/stream")
    public SseEmitter stream(
            @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,
            HttpSession session) {
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null || !EventStore.isOwner(sessionId, ((Number) userIdObj).longValue())) {
            SseEmitter denied = new SseEmitter(0L);
            denied.complete(); // 无权限：立即结束
            return denied;
        }
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 分钟超时

        //先快照历史事件，再订阅，避免漏推/重复推
        List<Map<String, String>> history = EventStore.all(sessionId);
        EventStore.subscribe(sessionId, emitter);
        for (Map<String, String> ev : history) {
            try {
                emitter.send(SseEmitter.event().name("event").data(ev));
            } catch (Exception e) {
                break; // 客户端已断开
            }
        }
        emitter.onCompletion(() -> EventStore.unsubscribe(sessionId, emitter));
        emitter.onTimeout(() -> EventStore.unsubscribe(sessionId, emitter));
        return emitter;
    }
}
