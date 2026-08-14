package managerAgent.hook;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * 内存事件库：按会话保存Agent运行事件，供 /events 查询
 */
public class EventStore {

    /** 单会话状态：事件队列 + 最终结果 + 取消标志 */
    public static class SessionState {
        final ConcurrentLinkedQueue<Map<String, String>> events = new ConcurrentLinkedQueue<>(); // 事件队列（线程安全）
        volatile String result = null;        // 最终结果缓存
        volatile boolean cancelRequested = false; // 中断请求标志
        volatile long lastAccessMs = System.currentTimeMillis(); // 最后访问时间（TTL清理用）
    }

    private static final ConcurrentHashMap<String, SessionState> SESSIONS = new ConcurrentHashMap<>(); // 会话隔离容器

    /** sessionId → 归属用户ID（/app 提交时绑定，防止跨用户越权读取/取消） */
    private static final ConcurrentHashMap<String, Long> OWNERS = new ConcurrentHashMap<>();

    /** sessionId → SSE 订阅者（实时推送事件，替代轮询） */
    private static final ConcurrentHashMap<String, Set<SseEmitter>> SUBSCRIBERS = new ConcurrentHashMap<>();

    private static final int MAX_EVENTS = 100; // 最多保留的事件条数（超出丢弃最旧的）

    private static final long SESSION_TTL_MS = 30 * 60 * 1000L; // 会话30分钟无活动即清理
    private static final int MAX_SESSIONS = 1000;               // 会话数量上限

    private static final String DEFAULT_SESSION = "default"; // 未传 sessionId 时的兜底会话

    /** 规范化会话键（空/未传 → default） */
    private static String key(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? DEFAULT_SESSION : sessionId;
    }

    /** 获取（不存在则创建）指定会话的状态 */
    private static SessionState state(String sessionId) {
        SessionState s = SESSIONS.computeIfAbsent(key(sessionId), k -> new SessionState());
        s.lastAccessMs = System.currentTimeMillis();
        if (SESSIONS.size() > MAX_SESSIONS) { // 超出上限时惰性清理过期会话
            cleanupExpired();
        }
        return s;
    }

    /** 清理过期会话及其归属（防止内存无限增长） */
    private static void cleanupExpired() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(e -> now - e.getValue().lastAccessMs > SESSION_TTL_MS);
        OWNERS.keySet().removeIf(k -> !SESSIONS.containsKey(k));
        SUBSCRIBERS.keySet().removeIf(k -> !SESSIONS.containsKey(k));
    }

    /** 注册 SSE 订阅者（指定会话） */
    public static void subscribe(String sessionId, SseEmitter emitter) {
        SUBSCRIBERS.computeIfAbsent(key(sessionId), k -> ConcurrentHashMap.newKeySet()).add(emitter);
    }

    /** 取消 SSE 订阅 */
    public static void unsubscribe(String sessionId, SseEmitter emitter) {
        Set<SseEmitter> set = SUBSCRIBERS.get(key(sessionId));
        if (set != null) {
            set.remove(emitter);
        }
    }

    /** 向指定会话的 SSE 订阅者推送事件 */
    private static void notify(String sessionId, Map<String, String> event) {
        Set<SseEmitter> set = SUBSCRIBERS.get(key(sessionId));
        if (set == null) {
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name("event").data(event));
            } catch (Exception e) {
                set.remove(emitter); // 客户端断开，移除订阅
            }
        }
    }

    /** 绑定 sessionId 归属用户（提交任务时调用） */
    public static void bindOwner(String sessionId, long userId) {
        OWNERS.put(key(sessionId), userId);
    }

    /** 校验 sessionId 是否属于该用户（无归属视为允许——此时无数据） */
    public static boolean isOwner(String sessionId, long userId) {
        Long owner = OWNERS.get(key(sessionId));
        return owner == null || owner == userId;
    }

    /**
     * 保存任务最终结果（按会话）
     */
    public static void setResult(String sessionId, String result) {
        state(sessionId).result = result;
    }

    /**
     * 获取任务最终结果（null=未完成）
     */
    public static String getResult(String sessionId) {
        return state(sessionId).result;
    }

    /**
     * 添加一条事件记录（按会话）
     */
    public static void add(String sessionId, String type, String content) {
        SessionState s = state(sessionId);
        Map<String, String> event = Map.of(
                "time", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                "type", type,
                "content", content == null ? "" : content
        );
        s.events.add(event);
        while (s.events.size() > MAX_EVENTS) { // 超出上限丢弃最旧事件
            s.events.poll();
        }
        notify(sessionId, event); // 实时推送给 SSE 订阅者
    }

    /**
     * 获取全部事件（最新的在后面）（按会话）
     */
    public static List<Map<String, String>> all(String sessionId) {
        return state(sessionId).events.stream().collect(Collectors.toList());
    }

    /**
     * 清空会话的事件、结果与取消标志
     */
    public static void clear(String sessionId) {
        SessionState s = state(sessionId);
        s.events.clear();
        s.result = null;
        s.cancelRequested = false;
    }

    /**
     * 请求中断当前任务
     */
    public static void requestCancel(String sessionId) {
        state(sessionId).cancelRequested = true;
    }

    /**
     * 检查是否有中断请求
     */
    public static boolean isCancelRequested(String sessionId) {
        return state(sessionId).cancelRequested;
    }

    /**
     * 列出全部会话的概览（管理端任务监控用）：会话ID、事件数、是否有结果、取消标志、归属用户、运行中状态
     */
    public static java.util.List<java.util.Map<String, Object>> listSessions() {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, SessionState> e : SESSIONS.entrySet()) {
            SessionState s = e.getValue();
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("sessionId", e.getKey());
            m.put("eventCount", s.events.size());
            m.put("hasResult", s.result != null);
            m.put("cancelled", s.cancelRequested);
            m.put("ownerId", OWNERS.get(e.getKey()));
            m.put("lastAccessMs", s.lastAccessMs);
            m.put("running", s.result == null && !s.events.isEmpty()); // 有事件但无结果 = 进行中
            out.add(m);
        }
        return out;
    }
}
