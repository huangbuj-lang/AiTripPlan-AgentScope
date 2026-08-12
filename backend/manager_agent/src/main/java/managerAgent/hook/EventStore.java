package managerAgent.hook;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * 内存事件库：保存Agent运行事件（用户输入/思考/工具调用），供 /events 查询
 * 事件只用于"实时查看"，无需持久化，用内存（零依赖零配置）
 *
 * ★会话隔离：2026-08-12 改造
 * 原实现是全局静态单例——多窗口/多用户同时用会互相污染（A的事件显示在B页面）。
 * 现改为 Map&lt;sessionId, SessionState&gt;：每个会话（前端localStorage生成）独立的事件队列+结果+取消标志，
 * 会话之间完全隔离；不传 sessionId 时回退到默认会话 "default"（向后兼容旧调用）。
 */
public class EventStore {

    /** 单会话状态：事件队列 + 最终结果 + 取消标志 */
    public static class SessionState {
        final ConcurrentLinkedQueue<Map<String, String>> events = new ConcurrentLinkedQueue<>(); // 事件队列（线程安全）
        volatile String result = null;        // 最终结果缓存
        volatile boolean cancelRequested = false; // 中断请求标志（/cancel 设置，planHook 检查）
    }

    private static final ConcurrentHashMap<String, SessionState> SESSIONS = new ConcurrentHashMap<>(); // 会话隔离容器

    private static final int MAX_EVENTS = 100; // 最多保留的事件条数（超出丢弃最旧的）

    private static final String DEFAULT_SESSION = "default"; // 未传 sessionId 时的兜底会话

    /** 获取（不存在则创建）指定会话的状态 */
    private static SessionState state(String sessionId) {
        String key = (sessionId == null || sessionId.isBlank()) ? DEFAULT_SESSION : sessionId;
        return SESSIONS.computeIfAbsent(key, k -> new SessionState());
    }

    /**
     * 保存任务最终结果（按会话）
     */
    public static void setResult(String sessionId, String result) {
        state(sessionId).result = result;
    }

    /**
     * 获取任务最终结果（null=还没完成）（按会话）
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
        while (s.events.size() > MAX_EVENTS) { // 超出上限，丢弃最旧的一条
            s.events.poll();
        }
    }

    /**
     * 获取全部事件（最新的在后面）（按会话）
     */
    public static List<Map<String, String>> all(String sessionId) {
        return state(sessionId).events.stream().collect(Collectors.toList());
    }

    /**
     * 清空会话的事件+结果+取消标志（每次新任务开始时调用，避免多任务混流）
     */
    public static void clear(String sessionId) {
        SessionState s = state(sessionId);
        s.events.clear();
        s.result = null;
        s.cancelRequested = false;
    }

    /**
     * 请求中断当前任务（/cancel 调用，planHook 每个事件检查该标志）
     */
    public static void requestCancel(String sessionId) {
        state(sessionId).cancelRequested = true;
    }

    /**
     * 检查是否有中断请求（planHook 事件循环中调用，有则抛异常终止 ReAct 循环）
     */
    public static boolean isCancelRequested(String sessionId) {
        return state(sessionId).cancelRequested;
    }
}
