package managerAgent.controller;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 通知 SSE 广播中心：按 userId 维护订阅的 SseEmitter 列表。
 * 通知写入 DB 后调用 {@link #push} 实时推给该用户所有在线窗口。
 */
public final class NotificationHub {

    private NotificationHub() {}

    /** userId → 该用户所有在线窗口的 emitter */
    private static final Map<Long, List<SseEmitter>> EMITTERS = new ConcurrentHashMap<>();

    /** 订阅：返回 emitter（onCompletion/onTimeout 时需调用 unsubscribe） */
    public static SseEmitter subscribe(long userId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 分钟超时
        EMITTERS.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> unsubscribe(userId, emitter));
        emitter.onTimeout(() -> unsubscribe(userId, emitter));
        return emitter;
    }

    public static void unsubscribe(long userId, SseEmitter emitter) {
        List<SseEmitter> list = EMITTERS.get(userId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                EMITTERS.remove(userId);
            }
        }
    }

    /** 推送一条通知 JSON 到该用户所有在线窗口 */
    public static void push(long userId, String json) {
        List<SseEmitter> list = EMITTERS.get(userId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(json));
            } catch (Exception ignored) {
                unsubscribe(userId, emitter); // 发送失败：清理断开的连接
            }
        }
    }
}
